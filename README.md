# jenkins-shared-library

Jenkins Global Pipeline Library. 앱 레포 `Jenkinsfile` 이 공유하는 재사용 step 모음.

JCasC 의 `globalLibraries` 가 본 레포를 `shared` 이름으로 등록 → 앱 `Jenkinsfile` 상단에서 로드.

```
jenkins-shared-library/
└── vars/
    ├── kanikoBuild.groovy   # Kaniko 빌드 + GHCR push step
    └── deployBump.groovy    # k8s-gitops manifest 이미지 태그 bump step
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

`kaniko` 컨테이너에서 실행 — podTemplate 이 GHCR 인증(`/kaniko/.docker/config.json`)을 projected volume 으로 mount 한다. `--cache=true --snapshot-mode=redo --use-new-run --ignore-path=/busybox --ignore-path=/home/jenkins` 등 안정성 옵션을 내부적으로 항상 적용 (durable-task hang 회피 — 상세는 `oci-terraform/kubernetes/platform/jenkins/README.md` §4 Kaniko podTemplate).

## deployBump

`k8s-gitops` 레포의 `manifests/<service>/kustomization.yaml` 이미지 태그를 불변 SHA 로 bump 하고 push.

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `service` | (필수) | 서비스명. `k8s-gitops/manifests/<service>/` 대상 |
| `image` | (필수) | `kustomization.yaml` 의 `images[].newName` 에 쓸 값 |
| `tag` | (필수) | `images[].newTag` 에 쓸 값 (불변 SHA 권장) |
| `gitops` | `k8s-gitops` | GitOps 레포 이름 (`https://github.com/${GH_ORG}/${gitops}.git`) |

동작: `jnlp` 컨테이너에서 `github-token` credential(JCasC `credentials-config`)로 `k8s-gitops` 를 shallow clone → `manifests/<service>/kustomization.yaml` 의 `newName`/`newTag` 를 `sed` 로 치환 → 변경 있으면 커밋(`ci: bump <service> to <tag>`) 후 `main` 에 직접 push, 변경 없으면 스킵(멱등).

앱 레포 main 브랜치는 건드리지 않음 — bump 커밋은 전부 `k8s-gitops` 에 쌓인다. `k8s-gitops` 는 Jenkins organizationFolder 의 `svc-.*` 스캔 대상 밖이라 빌드 루프가 없다(`[ci skip]` 불필요).
