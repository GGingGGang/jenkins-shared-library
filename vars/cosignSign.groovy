def call(Map config = [:]) {
    def image = config.image
    if (!image) {
        error 'cosignSign: image 필수 (예: ghcr.io/org/svc-core)'
    }
    def digestFile = config.digestFile ?: 'image-digest.txt'
    def keyPath    = config.keyPath    ?: '/cosign/key/cosign.key'
    def tlogUpload = config.tlogUpload != null ? config.tlogUpload : false

    if (!fileExists(digestFile)) {
        error "cosignSign: ${digestFile} 없음 — kanikoBuild 가 --digest-file 로 digest 를 기록했는지 확인"
    }
    def digest = readFile(digestFile).trim()
    if (!digest) {
        error "cosignSign: ${digestFile} 비어있음 — kaniko push 가 성공했는지 확인"
    }

    container('cosign') {
        sh "cosign sign --yes --tlog-upload=${tlogUpload} --key ${keyPath} ${image}@${digest}"
    }
}
