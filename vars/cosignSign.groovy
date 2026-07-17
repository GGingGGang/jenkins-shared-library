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

    // cosign v2 · 레거시 서명 포맷(.sig 태그) — kyverno 기본(Cosign) 검증 경로와 페어.
    // v3 bundle 포맷은 kyverno 가 raw key 검증 미지원(kyverno#16267). tlog 미사용은 검증측 rekor.ignoreTlog 와 세트.
    container('cosign') {
        sh "cosign sign --yes --tlog-upload=false --key ${keyPath} ${image}@${digest}"
    }
}
