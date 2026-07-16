def call(Map config = [:]) {
    def image = config.image
    if (!image) {
        error 'cosignSign: image 필수 (예: ghcr.io/org/svc-core)'
    }
    def digestFile    = config.digestFile    ?: 'image-digest.txt'
    def keyPath       = config.keyPath       ?: '/cosign/key/cosign.key'
    def signingConfig = config.signingConfig ?: 'cosign-signing-config.json'

    if (!fileExists(digestFile)) {
        error "cosignSign: ${digestFile} 없음 — kanikoBuild 가 --digest-file 로 digest 를 기록했는지 확인"
    }
    def digest = readFile(digestFile).trim()
    if (!digest) {
        error "cosignSign: ${digestFile} 비어있음 — kaniko push 가 성공했는지 확인"
    }

    // cosign v3: tlog/TSA 미사용은 --tlog-upload 가 아니라 해당 서비스 항목이 없는 signing config 로 지정
    writeFile file: signingConfig, text: libraryResource('cosign/signing-config.json')

    container('cosign') {
        sh "cosign sign --yes --signing-config ${signingConfig} --key ${keyPath} ${image}@${digest}"
    }
}
