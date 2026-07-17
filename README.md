# jenkins-shared-library

Jenkins Global Pipeline Library. 앱 레포 `Jenkinsfile` 이 공유하는 재사용 step 모음.

JCasC 의 `globalLibraries` 가 본 레포를 `shared` 이름으로 등록 → 앱 `Jenkinsfile` 상단에서 로드.

```
jenkins-shared-library/
├── vars/
│   ├── kanikoBuild.groovy     # Kaniko 빌드 + GHCR push step
│   ├── trivyImageScan.groovy  # Trivy 취약점 스캔 + HTML 리포트 게시 step
│   ├── cosignSign.groovy      # cosign 이미지 서명 step (digest 기준)
│   └── deployBump.groovy      # k8s-gitops manifest 이미지 태그 bump step
└── resources/
    └── trivy/html.tpl         # trivy 공식 HTML 리포트 템플릿 (체크인 — 버전 고정)
```

## 사용

앱 레포 `Jenkinsfile`:

```groovy
@Library('shared') _

pipeline {
  agent {
    kubernetes {
      label 'kaniko'
      defaultContainer 'kaniko'
    }
  }

  environment {
    SVC   = 'core'
    IMAGE = "ghcr.io/${env.GH_ORG.toLowerCase()}/svc-${SVC}"
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
        trivyImageScan(image: env.IMAGE, tag: env.TAG)
      }
    }

    stage('Sign') {
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
```

`agent.kubernetes.label 'kaniko'` 는 Jenkins JCasC 의 `agent.podTemplates.kaniko` (컨테이너명 `kaniko`)를 가리킨다. `env.GH_ORG` 는 JCasC `env-config` 가 주입. GHCR 이미지 경로는 대문자 GitHub 계정명 대응을 위해 `.toLowerCase()` 필수 — git clone URL 등 다른 용도는 원본 케이싱 유지 가능.

## kanikoBuild

Kaniko 로 빌드하고 GHCR 로 push.

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `image` | (필수) | 레지스트리 경로. 예: `ghcr.io/org/svc-core` |
| `context` | `.` | 빌드 컨텍스트 (`dir://...` 형식 가능) |
| `dockerfile` | `Dockerfile` | Dockerfile 경로 |
| `platform` | `linux/arm64` | 타겟 아키텍처 (Ampere A1) |
| `tags` | `[<git-commit 앞 7자리>, latest]` | push 태그 목록 |
| `cacheRepo` | `${image}/cache` | kaniko layer cache 레포 |
| `buildArgs` | `[:]` | Dockerfile `ARG` 로 전달할 key-value map |
| `digestFile` | `image-digest.txt` | kaniko `--digest-file` 출력 경로 — push 된 이미지 digest 기록(`cosignSign` 이 읽음) |

`kaniko` 컨테이너에서 실행 — podTemplate 이 GHCR 인증(`/kaniko/.docker/config.json`)을 projected volume 으로 mount 한다. `--cache=true --snapshot-mode=redo --use-new-run --ignore-path=/busybox --ignore-path=/home/jenkins` 등 안정성 옵션을 내부적으로 항상 적용 (durable-task hang 회피 — 상세는 [oci-always-free-k8s `kubernetes/platform/jenkins/README.md`](https://github.com/GGingGGang/oci-always-free-k8s/blob/main/kubernetes/platform/jenkins/README.md) §4 Kaniko podTemplate).

## trivyImageScan

이미지 취약점 스캔 + HTML 리포트를 빌드 페이지에 게시.

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `image` | (필수) | 스캔 대상 레지스트리 경로 |
| `tag` | `<git-commit 앞 7자리>` | 스캔 대상 태그 |
| `severity` | `CRITICAL` | 게이트 기준 심각도 |
| `ignoreUnfixed` | `true` | fix 미제공 CVE는 게이트·리포트에서 제외(`--ignore-unfixed`) |
| `gate` | `true` | `false`면 취약점 발견 시 빌드 실패 대신 `UNSTABLE` 표시 — 스캔·리포트는 그대로 남고 배포는 계속 |
| `reportFile` | `trivy-report.json` | 원본 스캔 결과(아티팩트) |
| `htmlFile` | `trivy-report.html` | 사람이 보는 리포트(아티팩트 + HTML Publisher) |

동작: `trivy` 컨테이너에서 `--format json`으로 1회만 스캔(`returnStatus: true`로 exit code 캡처 — 취약점 있어도 스텝이 바로 fail 하지 않음) → `trivy convert` 로 같은 json을 `resources/trivy/html.tpl`(공식 템플릿, 체크인) 기준 HTML 로 변환(재스캔 없음) → json/html 둘 다 `archiveArtifacts` → `publishHTML` 로 빌드 페이지에 **"Trivy Scan"** 탭 게시 → 마지막에 캡처해둔 exit code 로 실패 판정(`error`). 순서를 이렇게 잡은 이유: 스캔 실패 시에도 원인 확인용 HTML 리포트가 항상 먼저 게시되고, 그 다음에 파이프라인이 멈춰야 리포트를 못 보는 사고가 안 생김.

**Jenkins 쪽 전제 조건** ([oci-always-free-k8s `kubernetes/platform/jenkins/values.yaml`](https://github.com/GGingGGang/oci-always-free-k8s/blob/main/kubernetes/platform/jenkins/values.yaml)):
- `installPlugins`에 `htmlpublisher` 필요
- `controller.javaOpts` 로 `hudson.model.DirectoryBrowserSupport.CSP` 완화 필요 — Jenkins 기본 CSP(`default-src 'none'`)는 `publishHTML`이 서빙하는 페이지의 인라인 `<style>`/`<script>`를 막음. `html.tpl`이 외부 CDN 참조가 없는 걸 확인했으므로 `'self' 'unsafe-inline'` 범위로만 완화(전면 비활성화 아님).
- `agent.podTemplates.kaniko` 의 pod 에 `trivy` 컨테이너(`aquasec/trivy`, `sleep 99d`) 추가 필요

## cosignSign

push 된 이미지를 digest 기준으로 cosign 서명. 서명 아티팩트는 같은 레지스트리(GHCR)에 저장.

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `image` | (필수) | 서명 대상 레지스트리 경로 (태그 아닌 digest 로 서명) |
| `digestFile` | `image-digest.txt` | `kanikoBuild` 가 기록한 digest 파일 경로 |
| `keyPath` | `/cosign/key/cosign.key` | 마운트된 cosign 개인키 경로 |

동작: `kanikoBuild` 가 기록한 digest 파일을 읽어 `cosign` 컨테이너에서 `cosign sign --yes --key <keyPath> <image>@<digest>` 실행. 비밀번호는 podTemplate 의 `COSIGN_PASSWORD` env(Secret `cosign-key` key `password`)로 주입 — 스텝은 비번을 직접 다루지 않는다. 태그가 아닌 불변 digest 로 서명해 이후 태그가 재지정돼도 서명이 정확한 이미지를 가리킨다. 서명은 v3 기본 signing config(공개 sigstore)를 그대로 사용해 **Rekor tlog 항목이 bundle 에 포함**된다 — tlog 없는 bundle 은 `cosign verify`(기본값)와 Kyverno SigstoreBundle 검증이 모두 거부하기 때문(실측). 외부(Rekor) 호출은 서명 시점 1회뿐이고, 검증은 bundle 에 내장된 포함증명으로 오프라인 수행된다.

**Jenkins 쪽 전제 조건** ([oci-always-free-k8s `kubernetes/platform/jenkins/values.yaml`](https://github.com/GGingGGang/oci-always-free-k8s/blob/main/kubernetes/platform/jenkins/values.yaml)):
- `agent.podTemplates.kaniko` pod 에 `cosign` 컨테이너(shell 포함 이미지 — distroless cosign 은 `sleep`/`sh` 가 없어 사이드카로 못 씀) 추가
- `cosign-key` Secret(`build` NS, key `cosign.key`+`password`) 마운트 + `COSIGN_PASSWORD` env 주입

## deployBump

[k8s-gitops](https://github.com/GGingGGang/k8s-gitops) 레포의 `manifests/<service>/kustomization.yaml` 이미지 태그를 불변 SHA 로 bump 하고 push.

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `service` | (필수) | 서비스명. `k8s-gitops/manifests/<service>/` 대상 |
| `image` | (필수) | `kustomization.yaml` 의 `images[].newName` 에 쓸 값 |
| `tag` | (필수) | `images[].newTag` 에 쓸 값 (불변 SHA 권장) |
| `gitops` | `k8s-gitops` | GitOps 레포 이름 (`https://github.com/${GH_ORG}/${gitops}.git`) |

동작: `jnlp` 컨테이너에서 `github-token` credential(JCasC `credentials-config`)로 `k8s-gitops` 를 shallow clone → `manifests/<service>/kustomization.yaml` 의 `newName`/`newTag` 를 `sed` 로 치환 → 변경 있으면 커밋(`ci: bump <service> to <tag>`) 후 `main` 에 직접 push, 변경 없으면 스킵(멱등).

앱 레포 main 브랜치는 건드리지 않음 — bump 커밋은 전부 `k8s-gitops` 에 쌓인다. `k8s-gitops` 는 Jenkins organizationFolder 의 `svc-.*` 스캔 대상 밖이라 빌드 루프가 없다(`[ci skip]` 불필요).
