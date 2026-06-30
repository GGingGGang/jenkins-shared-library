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
                cd ${gitops}/manifests/${service}
                sed -i "s|newName:.*|newName: ${image}|" kustomization.yaml
                sed -i "s|newTag:.*|newTag: ${tag}|" kustomization.yaml
                git config user.email "ci@ggang.cloud"
                git config user.name "jenkins-ci"
                if git diff --quiet; then
                  echo "deployBump: ${service} 매니페스트 변경 없음 (${tag})"
                else
                  git commit -am "ci: bump ${service} to ${tag}"
                  git push origin HEAD:main
                fi
            """
        }
    }
}
