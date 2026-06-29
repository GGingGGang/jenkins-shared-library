# jenkins-shared-library

Jenkins Global Pipeline Library. 앱 레포 `Jenkinsfile` 이 공유하는 재사용 step 모음.

JCasC 의 `globalLibraries` 가 본 레포를 `shared` 이름으로 등록 → 앱 `Jenkinsfile` 상단에서 로드.

```
jenkins-shared-library/
└── vars/
    └── kanikoBuild.groovy   # Kaniko 빌드 + GHCR push step
```

## 사용

앱 레포 `Jenkinsfile`:

```groovy
@Library('shared') _

pipeline {
  agent {
    kubernetes {
      inheritFrom 'kaniko'
      defaultContainer 'kaniko'
    }
  }
  stages {
    stage('Build & Push') {
      steps {
        kanikoBuild(image: "ghcr.io/${env.GH_ORG}/k8s-test-login-server")
      }
    }
  }
}
```

## kanikoBuild

| 파라미터 | 기본값 | 설명 |
|----------|--------|------|
| `image` | (필수) | 레지스트리 경로. 예: `ghcr.io/org/repo` |
| `context` | `.` | 빌드 컨텍스트 |
| `dockerfile` | `Dockerfile` | Dockerfile 경로 |
| `platform` | `linux/arm64` | 타겟 아키텍처 (Ampere A1) |
| `tags` | `[<short-sha>, latest]` | push 태그 목록 |

`kaniko` 컨테이너에서 실행 — podTemplate 이 GHCR 인증(`/kaniko/.docker/config.json`)을 mount 한다.
