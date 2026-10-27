# 서버 CI와 배포 산출물 계약

## 실행과 검증 범위

`.github/workflows/server-ci.yml`의 Server CI는 develop 대상 PR, develop push, 수동 실행에서 동작한다. Ubuntu 24.04 / Temurin Java 21 / 저장소 Gradle Wrapper를 사용한다.

1. 체크아웃 후 Gradle Wrapper 무결성 검사와 Gradle 캐시를 준비한다.
2. Python 표준 unittest로 산출물 계약 6개를 검사한다.
3. `./gradlew --no-daemon test bootJar`로 전체 기본 회귀와 실행 JAR를 빌드한다.
4. 실패한 실행에서도 생성된 테스트 보고서를 보관한다.
5. 앞선 단계가 모두 성공한 경우에만 실행 JAR·체크섬·메타데이터를 묶어 업로드한다.

PR은 GitHub가 만든 병합 커밋을 기본 체크아웃하므로 develop과 합친 결과를 검증한다. PR 브랜치 head SHA와 실제 테스트한 commit SHA는 다를 수 있다.

CI는 운영 DB·AWS·카카오·LINER·OpenAI·PayApp의 비밀값을 전달하지 않는다. Spring 테스트별 test 프로필/H2와 로컬 모의 서버를 사용한다. 앱 기본 프로필을 환경변수로 test에 고정하지 않아 기존 `@ActiveProfiles`별 검증도 유지한다.

CI는 `scripts/install-ci-mysql84.py`로 공식 MySQL8.4.11 Linux 최소 바이너리를 SHA256 검증 후 runner 임시 폴더에 설치하고, `python3 scripts/mysql84-test.py`로 실제 SQL/Flyway/JDBC 회귀와 패키징된 JAR의 전용 모드를 검증한다. Docker·시스템 MySQL·운영 RDS를 사용하지 않는다. 이 단계 실패 시 JAR 아티팩트 게시가 중단된다. 로컬 실행 전에는 `./gradlew --no-daemon test bootJar`로 JAR를 준비한다. 자세한 절차는 [DB 마이그레이션](database-migrations.md)을 따른다.

## 권한·캐시·동시 실행

- 워크플로 권한은 contents:read이며 OIDC/AWS 배포 권한이 없다. Checkout의 Git 인증 정보 영구 저장도 끈다.
- 외부 Actions는 확인한 커밋 SHA로 고정한다. 버전 변경 시 공식 릴리스와 SHA를 함께 확인한다.
- setup-gradle의 basic 캐시를 사용한다. develop 외 ref는 읽기 전용이며 setup-java와 중복 캐시를 구성하지 않는다.
- PR별 진행 중 CI는 새 커밋으로 취소할 수 있다. develop push 실행은 진행 중인 검증을 자동 취소하지 않는다.
- job 제한은20분, 테스트 보고서·JAR 아티팩트 보관은7일이다.
- 테스트 실패를 무시하는 continue-on-error나, 실패 후에도 배포 JAR를 업로드하는 always 조건을 사용하지 않는다. always 조건은 보고서에만 적용한다.

## 산출물

| 이름 | 내용 |
|---|---|
| test-reports-<runId>-<runAttempt> | 기본 테스트 HTML/XML 및 생성된 Gradle 문제 보고서 |
| server-jar-<testedCommitSha>-<runId>-<runAttempt> | 검증된 실행 JAR·SHA256SUMS·build-metadata.json |

배포 묶음에는 다음 세 파일만 들어간다.

```text
mongle-<전체-테스트-커밋-SHA>.jar
SHA256SUMS
build-metadata.json
```

패키징 스크립트는 plain.jar를 제외한 후보가 정확히 하나인지, Spring Boot JarLauncher/Start-Class/BOOT-INF 구조가 있는지, GitHub SHA와 체크아웃 HEAD가 같은지, 추적된 소스가 깨끗한지 검사한다. 모호한 후보·잘못된 ZIP·symlink 입력/출력 등은 실패한다. 환경 파일·소스 전체·작업 디렉터리를 함께 압축하지 않는다.

SHA256SUMS는 묶음 디렉터리에서 검증할 수 있는 상대 파일명을 사용한다. GitHub가 표시하는 전체 업로드 아카이브 digest와 실행 JAR 자체의 SHA-256은 구분한다.

메타데이터 schemaVersion=1의 필드는 다음과 같다.

| 필드 | 의미 |
|---|---|
| repository | GitHub 저장소 |
| commitSha | 실제 체크아웃·테스트한 전체 Git SHA |
| headSha | PR head SHA. PR 외에는 commitSha와 동일 |
| eventName / ref | push/pull_request/workflow_dispatch 및 실행 ref |
| runId / runAttempt | GitHub 실행과 재실행 식별자 |
| jar / sha256 | 묶음 내 파일명과 실제 JAR 체크섬 |
| deploymentEligible | 이 저장소의 develop push인지 나타내는 힌트 |

PR 산출물과 수동 실행 산출물은 deploymentEligible=false다. 로컬 `python3 scripts/package-ci-artifact.py`도 기본 local/false로 생성한다. 이 플래그 자체는 배포 권한이나 CI 성공 증명이 아니다.

## 후속 CD가 확인할 것

CD는 GitHub API 기준으로 저장소·워크플로·push 이벤트·develop 브랜치·전체 커밋 SHA·성공한 실행/시도를 확인한 뒤 그 실행의 산출물만 가져온다. PR에서 만든 묶음을 운영 배포하지 않는다. 파일 안의 metadata나 아티팩트 이름만 믿고 권한을 부여하지 않는다.

다운로드 후 SHA256SUMS와 metadata의 sha256/commitSha를 대조한다. 다시 빌드하지 않고 동일 JAR를 S3/EC2로 전달한다. 이 CI에는 실제 배포·systemd 재시작·SQL 적용·환경 파일 변경 단계가 없다.

## 로컬 확인

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v
./gradlew --no-daemon test bootJar
python3 scripts/package-ci-artifact.py
```

패키징은 추적된 변경을 커밋한 뒤 실행한다. `build/ci-artifact/`는 매번 교체되는 생성 디렉터리다. 새 패키징이 실패하면 이전 묶음을 재사용하지 않는다. 위 검증 중 JAR 빌드 성공과 CI 전체 성공을 혼동하지 않는다.

## PR 확인과 필수 체크

PR의 Checks에서 Server CI의 **Test and build** job 결과를 확인한다. 실패 단계의 로그와 test-reports 아티팩트를 확인하고, 성공 실행에는 server-jar 묶음까지 있는지 확인한다. 첫 워크플로가 develop에 머지되기 전에는 수동 실행 메뉴가 아직 노출되지 않을 수 있다.

develop Ruleset/Branch protection을 관리할 때 실제로 생성된 `Test and build` 체크를 필수 항목으로 선택한다. 경로 필터로 필수 체크가 실행되지 않는 상태를 만들지 않는다. 이 PR의 워크플로 추가만으로 저장소 관리 설정이 자동 변경되지는 않는다. 관리 설정 변경은 팀의 머지 정책과 함께 적용한다.

공식 참고: [GitHub Java/Gradle CI](https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-gradle), [setup-gradle](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md), [upload-artifact](https://github.com/actions/upload-artifact).
