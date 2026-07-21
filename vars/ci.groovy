def call(Map cfg = [:]) {
    def service = cfg.service
    if (!service) {
        error 'ci: service 필수 (예: ci(service: "auth"))'
    }

    def parsed   = readYaml(text: libraryResource('ci/services.yaml'))
    def defaults = parsed.defaults ?: [:]
    def svc      = (parsed.services ?: [:])[service] ?: [:]
    def conf     = defaults + svc

    pipeline {
        agent {
            kubernetes {
                label 'kaniko'
                defaultContainer 'kaniko'
            }
        }

        environment {
            SVC   = service
            IMAGE = "ghcr.io/${env.GH_ORG.toLowerCase()}/svc-${service}"
            TAG   = "${env.GIT_COMMIT}"
        }

        options {
            timestamps()
            disableConcurrentBuilds()
        }

        stages {
            stage('Build & Push') {
                steps {
                    kanikoBuild(
                        image: env.IMAGE,
                        context: "dir://${env.WORKSPACE}",
                        tags: [env.TAG, 'latest'],
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
    }
}
