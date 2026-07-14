def call(Map config = [:]) {
    def image = config.image
    if (!image) {
        error 'trivyImageScan: image 필수 (예: ghcr.io/org/repo)'
    }
    def tag           = config.tag           ?: (env.GIT_COMMIT ?: 'latest').take(7)
    def severity      = config.severity       ?: 'CRITICAL'
    def ignoreUnfixed = config.ignoreUnfixed != null ? config.ignoreUnfixed : true
    def reportFile    = config.reportFile    ?: 'trivy-report.json'
    def htmlFile      = config.htmlFile      ?: 'trivy-report.html'
    def templateFile  = 'trivy-html.tpl'

    writeFile file: templateFile, text: libraryResource('trivy/html.tpl')

    def status = container('trivy') {
        def target      = "${image}:${tag}"
        def unfixedFlag = ignoreUnfixed ? '--ignore-unfixed' : ''
        def scanStatus = sh(
            script: """
                trivy image \\
                  --severity ${severity} \\
                  --exit-code 1 \\
                  ${unfixedFlag} \\
                  --format json \\
                  --output ${reportFile} \\
                  ${target}
            """,
            returnStatus: true
        )
        sh """
            trivy convert \\
              --format template \\
              --template "@${templateFile}" \\
              --output ${htmlFile} \\
              ${reportFile}
        """
        scanStatus
    }

    archiveArtifacts artifacts: "${reportFile},${htmlFile}", allowEmptyArchive: true
    publishHTML(target: [
        reportName           : 'Trivy Scan',
        reportDir             : '.',
        reportFiles           : htmlFile,
        keepAll               : true,
        alwaysLinkToLastBuild : true,
        allowMissing          : true
    ])

    if (status != 0) {
        error "trivyImageScan: ${severity} 취약점 발견 (fix 가능) — 'Trivy Scan' 리포트 참고"
    }
}
