# jenkins-shared-library

Jenkins Global Pipeline Library. 앱 레포 `Jenkinsfile` 이 공유하는 재사용 step 모음.

JCasC 의 `globalLibraries` 가 본 레포를 `shared` 이름으로 등록 → 앱 `Jenkinsfile` 상단에서 로드.

```
jenkins-shared-library/
├── vars/
│   ├── ci.groovy              # 전체 파이프라인 조립 step — 앱 레포는 이것만 호출
│   ├── kanikoBuild.groovy     # Kaniko 빌드 + GHCR push step
│   ├── trivyImageScan.groovy  # Trivy 취약점 스캔 + HTML 리포트 게시 step
│   ├── cosignSign.groovy      # cosign 이미지 서명 step (digest 기준)
│   └── deployBump.groovy      # k8s-gitops manifest 이미지 태그 bump step
└── resources/
    ├── trivy/html.tpl         # trivy 공식 HTML 리포트 템플릿 (체크인 — 버전 고정)
    └── ci/services.yaml       # 서비스별 파이프라인 설정 — CI 사실의 단일 소스
```

## 사용

앱 레포 `Jenkinsfile` 은 이 두 줄이 전부:

```groovy
@Library('shared') _

ci(service: 'auth')
```

`ci()` 가 `pipeline{}` 전체(agent·stages)를 조립한다 — Build & Push → Image Scan → Sign(서비스별 on/off) → Bump. `ci()` 는 `service` 외에 다른 파라미터를 받지 않는다 — 스캔 게이트·서명 여부 같은 보안 정책은 앱 레포 `Jenkinsfile` 이 넘길 수 없고, 오직 `resources/ci/services.yaml`(본 레포 소유 — 앱 레포는 쓰기 권한 없음)로만 정해진다:

```yaml
defaults:
  scanGate: true   # true = 취약점 발견 시 빌드 실패, false = UNSTABLE 로 통과
  sign: true

services:
  auth:
    language: node
  core:
    language: go
  batch:
    language: java
```

`language` 는 서비스 스택 메타데이터 — 파이프라인 분기에는 아직 안 쓰고(`ci.groovy` 미참조) 사람/후속 자동화용 기록.

**목록에 없는 서비스는 자동으로 `defaults`(게이트 on, 서명 on)로 동작** — 예외 없는 평범한 신규 서비스는 이 파일을 전혀 건드릴 필요가 없다(온보딩 = 앱 레포 + k8s-gitops + 인프라 레포의 네임스페이스, 3곳). `services.yaml` 수정은 특정 서비스에 기본 정책과 다른 예외를 승인할 때만 발생하는 별도 이벤트고, 그 결정은 이 레포(앱 레포가 아님) 소유로 남는다.

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `service` | (필수) | `services.yaml` 조회 키이자 `IMAGE`/`Bump` 대상 서비스명 |

**Jenkins 쪽 전제 조건**: `installPlugins`에 `pipeline-utility-steps` 필요 (`readYaml` 스텝 — `services.yaml` 파싱용).

`agent.kubernetes.label 'kaniko'` 는 Jenkins JCasC 의 `agent.podTemplates.kaniko` (컨테이너명 `kaniko`)를 가리킨다. `env.GH_ORG` 는 JCasC `env-config` 가 주입. GHCR 이미지 경로는 대문자 GitHub 계정명 대응을 위해 `.toLowerCase()` 필수 — git clone URL 등 다른 용도는 원본 케이싱 유지 가능.

아래 4개 step은 `ci()` 가 내부적으로 호출하는 빌딩 블록 — 직접 쓸 일은 거의 없지만 파라미터는 `ci()` 동작을 이해하는 데 필요.

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

동작: `kanikoBuild` 가 기록한 digest 파일을 읽어 `cosign` 컨테이너에서 `cosign sign --yes --tlog-upload=false --key <keyPath> <image>@<digest>` 실행. 비밀번호는 podTemplate 의 `COSIGN_PASSWORD` env(Secret `cosign-key` key `password`)로 주입 — 스텝은 비번을 직접 다루지 않는다. 태그가 아닌 불변 digest 로 서명해 이후 태그가 재지정돼도 서명이 정확한 이미지를 가리킨다. 서명은 **cosign v2 레거시 포맷**(`sha256-<digest>.sig` 태그)으로 같은 레포에 저장되며 tlog 미사용(자체완결) — 검증측 Kyverno 기본(Cosign) 경로의 `rekor.ignoreTlog: true` 와 세트다. v3 bundle 포맷을 쓰지 않는 이유: Kyverno 가 bundle 의 raw key 검증을 아직 지원하지 않음 (kyverno#16267, 실측 확인 2026-07-17).

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

여러 서비스가 동시에 `main` 에 push 할 때 non-fast-forward 로 거부되는 경우 최대 5회 재시도: `git fetch --depth 1` + `git reset --hard origin/main` 으로 최신을 받은 뒤 `sed` 를 재적용해 재커밋·재push (서비스별로 다른 파일을 건드리므로 내용 충돌 없음 — 순수 push 순서 경합만 해소).

앱 레포 main 브랜치는 건드리지 않음 — bump 커밋은 전부 `k8s-gitops` 에 쌓인다. `k8s-gitops` 는 Jenkins organizationFolder 의 `svc-.*` 스캔 대상 밖이라 빌드 루프가 없다(`[ci skip]` 불필요).
