import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import java.time.Instant

def call(Map cfg = [:]) {
    def service = cfg.service
    if (!service) {
        error 'ci: service 필수 (예: ci(service: "auth"))'
    }

    def parsed   = readYaml(text: libraryResource('ci/services.yaml'))
    def defaults = parsed.defaults ?: [:]
    def svc      = (parsed.services ?: [:])[service] ?: [:]
    def conf     = defaults + svc
    def lang     = (parsed.languages ?: [:])[conf.language]
    if (!lang?.testImage || !lang?.testCmd) {
        error "ci: '${service}' 의 language('${conf.language}') 가 services.yaml languages 에 정의돼 있지 않음 (testImage/testCmd 필수)"
    }

    pipeline {
        agent {
            kubernetes {
                inheritFrom 'kaniko'
                namespace 'build'
                defaultContainer 'kaniko'
                yamlMergeStrategy merge()
                yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: test
      image: ${lang.testImage}
      command: ["sleep"]
      args: ["99d"]
      resources:
        requests:
          cpu: 100m
          memory: 256Mi
        limits:
          memory: 1536Mi
"""
            }
        }

        environment {
            SVC   = "${service}"
            IMAGE = "ghcr.io/${env.GH_ORG.toLowerCase()}/svc-${service}"
            TAG   = "${env.GIT_COMMIT}"
        }

        options {
            timestamps()
            disableConcurrentBuilds()
        }

        stages {
            stage('Test') {
                steps {
                    container('test') {
                        sh lang.testCmd
                    }
                }
            }

            stage('Build & Push') {
                steps {
                    kanikoBuild(
                        image: env.IMAGE,
                        context: "dir://${env.WORKSPACE}",
                        tags: [env.TAG],
                        buildArgs: [GIT_SHA: env.GIT_COMMIT]
                    )
                }
            }

            stage('Image Scan') {
                steps {
                    trivyImageScan(
                        image: env.IMAGE,
                        tag: env.TAG,
                        gate: conf.scanGate
                    )
                }
            }

            stage('Sign') {
                when { expression { conf.sign == true } }
                steps {
                    cosignSign(image: env.IMAGE)
                }
            }

            stage('Bump') {
                steps {
                    deployBump(service: env.SVC, image: env.IMAGE, tag: env.TAG)
                }
            }
        }

        post {
            failure {
                script {
                    try {
                        sendFailureAlert(service, env.JOB_NAME, env.BUILD_NUMBER, env.BUILD_URL ?: '')
                    } catch (Exception e) {
                        echo "Alertmanager notification failed: ${e.message}"
                    }
                }
            }
        }
    }
}

@NonCPS
def sendFailureAlert(String service, String job, String build, String buildUrl) {
    def alert = [[
        labels: [alertname: 'JenkinsBuildFailed', severity: 'warning', namespace: 'cicd', service: service, job: job, build: build],
        annotations: [summary: "${job} #${build} failed", description: buildUrl],
        endsAt: Instant.now().plusSeconds(600).toString()
    ]]
    def connection = (HttpURLConnection) new URL('http://kps-kube-prometheus-stack-alertmanager.monitoring.svc.cluster.local:9093/api/v2/alerts').openConnection()
    try {
        connection.setRequestMethod('POST')
        connection.setConnectTimeout(3000)
        connection.setReadTimeout(3000)
        connection.setDoOutput(true)
        connection.setRequestProperty('Content-Type', 'application/json')
        def output = connection.getOutputStream()
        try {
            output.write(JsonOutput.toJson(alert).getBytes('UTF-8'))
        } finally {
            output.close()
        }
        if (connection.getResponseCode() != 200) {
            throw new IOException("Alertmanager returned HTTP ${connection.getResponseCode()}")
        }
    } finally {
        connection.disconnect()
    }
}
