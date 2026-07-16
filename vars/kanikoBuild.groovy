def call(Map config = [:]) {
    def image = config.image
    if (!image) {
        error 'kanikoBuild: image 필수 (예: ghcr.io/org/repo)'
    }
    def context    = config.context    ?: '.'
    def dockerfile = config.dockerfile ?: 'Dockerfile'
    def platform   = config.platform   ?: 'linux/arm64'
    def tags       = config.tags       ?: [(env.GIT_COMMIT ?: 'latest').take(7), 'latest']
    def cacheRepo  = config.cacheRepo  ?: "${image}/cache"
    def buildArgs  = config.buildArgs  ?: [:]
    def digestFile = config.digestFile ?: 'image-digest.txt'

    container('kaniko') {
        def destinations = tags.collect { "--destination=${image}:${it}" }
        def extraArgs    = buildArgs.collect { k, v -> "--build-arg=${k}=${v}" }
        def cmd = ([
            '/kaniko/executor',
            "--context=${context}",
            "--dockerfile=${dockerfile}",
            "--customPlatform=${platform}",
            "--digest-file=${digestFile}",
        ] + destinations + extraArgs + [
            '--cache=true',
            "--cache-repo=${cacheRepo}",
            '--cache-ttl=168h',
            '--snapshot-mode=redo',
            '--use-new-run',
            '--ignore-path=/busybox',
            '--ignore-path=/home/jenkins',
        ]).join(' ')
        sh cmd
    }
}
