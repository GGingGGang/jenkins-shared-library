def call(Map config = [:]) {
    def image = config.image
    if (!image) {
        error 'cosignSign: image 필수 (예: ghcr.io/org/svc-core)'
    }
    def digestFile = config.digestFile ?: 'image-digest.txt'
    def keyPath    = config.keyPath    ?: '/cosign/key/cosign.key'

    if (!fileExists(digestFile)) {
        error "cosignSign: ${digestFile} 없음 — kanikoBuild 가 --digest-file 로 digest 를 기록했는지 확인"
    }
    def digest = readFile(digestFile).trim()
    if (!digest) {
        error "cosignSign: ${digestFile} 비어있음 — kaniko push 가 성공했는지 확인"
    }

    // v3 기본 signing config(공개 sigstore) 사용 — Rekor tlog 포함 서명.
    // tlog 없는 bundle 은 cosign verify·kyverno 모두 기본 거부 — 검증은 bundle 내 포함증명으로 오프라인.
    container('cosign') {
        sh "cosign sign --yes --key ${keyPath} ${image}@${digest}"
    }
}
