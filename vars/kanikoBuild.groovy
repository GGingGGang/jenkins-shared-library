def call(Map config = [:]) {
    def image = config.image
    if (!image) {
        error 'kanikoBuild: image 필수 (예: ghcr.io/org/repo)'
    }
    def context    = config.context    ?: '.'
    def dockerfile = config.dockerfile ?: 'Dockerfile'
    def platform   = config.platform   ?: 'linux/arm64'
    def tags       = config.tags       ?: [(env.GIT_COMMIT ?: 'latest').take(7), 'latest']

    container('kaniko') {
        def destinations = tags.collect { "--destination=${image}:${it}" }.join(' ')
        sh "/kaniko/executor --context=${context} --dockerfile=${dockerfile} --customPlatform=${platform} --cache=true ${destinations}"
    }
}
