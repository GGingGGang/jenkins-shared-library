def call(Map cfg = [:]) {
    def service = cfg.service
    def image   = cfg.image
    def tag     = cfg.tag
    if (!service || !image || !tag) {
        error 'deployBump: service, image, tag 필수'
    }
    def gitops = cfg.gitops ?: 'k8s-gitops'

    container('jnlp') {
        withCredentials([usernamePassword(credentialsId: 'github-token', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_TOKEN')]) {
            sh """
                set -eu +x
                rm -rf ${gitops}
                git clone --depth 1 "https://\${GIT_USER}:\${GIT_TOKEN}@github.com/${env.GH_ORG}/${gitops}.git"
                cd ${gitops}
                git config user.email "ci@ggang.cloud"
                git config user.name "jenkins-ci"

                sed -i "s|newName:.*|newName: ${image}|" manifests/${service}/kustomization.yaml
                sed -i "s|newTag:.*|newTag: ${tag}|" manifests/${service}/kustomization.yaml
                if git diff --quiet; then
                  echo "deployBump: ${service} 매니페스트 변경 없음 (${tag})"
                  exit 0
                fi
                git commit -am "ci: bump ${service} to ${tag}"

                for i in 1 2 3 4 5; do
                  if git push origin HEAD:main; then
                    exit 0
                  fi
                  echo "deployBump: push 거부(동시 커밋 경합) — 재시도 \$i/5"
                  git fetch --depth 1 origin main
                  git reset --hard origin/main
                  sed -i "s|newName:.*|newName: ${image}|" manifests/${service}/kustomization.yaml
                  sed -i "s|newTag:.*|newTag: ${tag}|" manifests/${service}/kustomization.yaml
                  if git diff --quiet; then
                    echo "deployBump: 재시도 중 이미 반영됨 (${tag})"
                    exit 0
                  fi
                  git commit -am "ci: bump ${service} to ${tag}"
                done
                echo "deployBump: 재시도 5회 소진 — push 실패"
                exit 1
            """
        }
    }
}
