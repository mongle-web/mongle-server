# EC2 자동 배포 (#76)

`develop` push의 **Server CI 성공 → Server CD → DB 마이그레이션 → 동일 JAR 배포** 순서다. PR 리뷰·머지 외 별도 배포 승인은 없다. PR·수동 실행 CI 산출물은 배포하지 않는다. `MONGLE_CD_ENABLED`는 최초 설정/장애 대응용 스위치이며, 매번 승인을 요구하는 기능이 아니다.

## 현재 준비와 적용 경계

EC2·SSM 온라인·GitHub OIDC 공급자·GitHub 배포 역할·서울 배포 S3·서버 `/snap/bin/aws` 설치는 전달된 화면/로그로 확인됐다. IAM 권한·아래 스크립트/SSM Document·GitHub 변수와 최초 실배포 검증은 별도 적용해야 한다. 이 코드의 존재만으로 운영 자동 배포 완료를 의미하지 않는다.

- 배포 대상: 서울 `i-0ebfbdbacc2b3cc1b`, `mongle.service`.
- GitHub 역할: `arn:aws:iam::800417964658:role/mongle-github-deploy-role`.
- EC2 역할: `mongle-prod-ec2-role`. 기존 `AmazonSSMManagedInstanceCore`는 유지한다.
- S3: `mongle-prod-deploy-800417964658`, 배포 전용 `releases/`.
- 기존 릴리스: `/opt/mongle/releases/mongle-f506c173.jar`.
- 서비스 사용자/그룹: `mongle:mongle`, `/opt/mongle/current.jar` 링크 사용.
- 애플리케이션: Java21 JAR, 127.0.0.1:8080, Nginx/HTTPS 앞단. Docker를 사용하지 않는다.

## 배포 검증과 흐름

1. 기본 브랜치의 `workflow_run`이 Server CI 성공을 수신한다. 같은 저장소·develop·push·성공 조건 외에는 작업을 실행하지 않는다.
2. `verify_ci.py`는 GitHub API에서 워크플로 ID/경로·커밋·현재 develop HEAD·최신 실행 attempt·성공한 `Test and build` 작업·정확한 artifact ID를 재확인한다. JSON의 `deploymentEligible`만 신뢰하지 않는다.
3. 해당 CI attempt의 산출물 하나만 내려받고 JAR/체크섬/metadata의 정확한 파일 목록·소유 실행·SHA256을 대조한다. 다시 빌드하지 않는다.
4. 검증 후 OIDC 임시 AWS 권한을 받고 S3 `releases/<commit>/<ci-run-id>/<attempt>/`에 파일 3개를 업로드한다. `If-None-Match: *`로 덮어쓰지 않으며 동일 파일 재전송만 허용한다.
5. 업로드 후 develop HEAD를 다시 확인하고 지정 EC2·`Mongle-DeployV1`의 **고정 숫자 버전과 SHA256**으로 SSM 명령을 보낸다. GitHub 역할은 임의 Document/서버/셸을 실행하거나 Document를 수정하지 못한다.
6. root가 설치한 고정 `deploy-flyway.py`가 `flock`으로 동시에 하나만 실행한다. SSM 입력은 숫자/해시와 고정 `MigrationProtocol=flyway-v1`만 허용하며 `ENV_VAR` 보간으로 전달한다. 버킷·실행 파일·서비스·경로·URL을 명령 파라미터로 바꿀 수 없다.
7. 진행 중이던 배포의 `pending` 기록이 있으면 먼저 이전 릴리스로 복구한다. CI run number/attempt보다 오래된 명령은 거절한다. 실행 번호는 같은 Server CI 워크플로 기준이다.
8. JAR를 다운로드해 SHA256·BootJar 형식을 검증하고 배치한 뒤 기존 릴리스 health를 확인한다. 같은 검증 JAR의 보호된 복사본을 전용 계정으로 실행해 Flyway를 검증/적용한다. 실패하면 링크 교체를 중단한다. 성공하면 앱 복구 지점을 기록하고 링크를 교체한다. 다운로드·마이그레이션 동안 앱을 중지하지 않으며 DB를 자동 되돌리지 않는다.
9. `systemctl restart mongle.service` 후 내부·공개 HTTPS health가 같은 회차에 모두 HTTP200/UP인지 확인한다. 실패·예외·TERM/INT는 이전 링크 복구·재시작·health 재확인 후 실패로 종료한다. 복구 실패 기록은 다음 배포까지 보존한다.
10. GitHub는 SSM 완료 결과를 확인하고 CI SHA/체크섬/Command ID/결과만 30일 보관한다. 결과 불명확/시간 초과 시 명령을 취소하거나 새 배포를 자동 발행하지 않는다.

현재 단일 systemd 프로세스를 재시작하므로 **기동 시간 동안 짧은 중단이 있다**. 무중단/블루그린 배포를 제공하는 구조는 아니다. SIGKILL·EC2 장애·호스트 네트워크 단절에서는 즉시 복구를 보장할 수 없으며, pending 복구와 아래 수동 절차를 사용한다. 서비스가 이미 비정상이면 자동 교체를 거절하므로 먼저 수동으로 정상 릴리스를 복구한다.

최초 도입 시 [DB 마이그레이션의 baseline·전용 계정·새 SSM 설치](database-migrations.md)를 먼저 완료한다. 기존 SSM 문서 버전과 설치 파일만으로 새 DB 단계를 실행할 수 없다.

## 1. 인프라 JSON 생성 — 로컬 저장소

리뷰할 브랜치에서 실행한다. AWS 호출이나 서버 변경은 발생하지 않는다.

```bash
python3 scripts/cd/render_infra.py --bucket mongle-prod-deploy-800417964658 --instance-id i-0ebfbdbacc2b3cc1b
```

`build/cd-infra/`에 다음 파일을 만든다.

| 파일 | 적용 위치 |
|---|---|
| github-trust.json | GitHub 배포 역할 → 신뢰 관계 편집 |
| github-policy.json | GitHub 배포 역할 → 인라인 정책 추가 |
| ec2-policy.json | 기존 EC2 역할 → 인라인 정책 추가 |
| bucket-policy.json | 배포용 S3 → 권한 → 버킷 정책 |
| ssm-deploy.json | Systems Manager → 문서 → 명령 문서 생성 |
| deploy.json | EC2 `/etc/mongle/deploy.json` |

계정/저장소/문서/서비스는 이 운영 환경에 맞춘 고정 값이다. 다른 운영 환경에 재사용할 때에는 renderer와 OIDC 식별자·워크플로 account 검사를 함께 리뷰한다.

## 2. AWS 콘솔 설정 — IAM/S3/SSM

- IAM → `mongle-github-deploy-role`: 신뢰 관계를 `github-trust.json`과 대조한다. 해당 저장소는 immutable subject를 사용하므로 `@ownerId`·`@repositoryId`를 지우지 않는다.
- 같은 역할 → 권한 추가 → 인라인 정책 생성 → JSON에 `github-policy.json`, 이름 `MongleDeployPublishAndCommand`.
- IAM → `mongle-prod-ec2-role` → 인라인 정책 추가 → `ec2-policy.json`, 이름 `MongleDeployArtifactRead`. 기존 SSM 정책/신뢰 관계는 교체하지 않는다.
- S3 → 해당 버킷 → 권한/속성: 모든 퍼블릭 액세스 차단·ACL 비활성·SSE-S3·버전 관리 비활성을 확인한다. `bucket-policy.json`으로 HTTPS 전송을 요구한다. 기존 정책이 따로 있다면 병합해서 보존한다.
- S3 lifecycle은 MVP 첫 배포/복구 검증 후 릴리스 보관 기간을 정해 적용한다. 이 배포기는 이전 JAR를 자동 삭제하지 않는다. 즉시 만료/삭제 규칙을 추가하지 않는다.
- Systems Manager(서울) → 문서 → 문서 생성 → **명령 또는 세션** → 이름 `Mongle-DeployV1`, 문서 유형 **Command**, JSON에 `ssm-deploy.json`. 스키마 2.2/ENV_VAR를 그대로 유지한다. Agent3.3.4793.0은 이 보간 기능의 요구 버전보다 높다.

SSM 문서 생성 후 AWS CloudShell 등 관리자 환경에서 다음 조회만 실행한다. EC2 역할에는 이 관리 작업 권한을 추가하지 않는다.

```bash
aws ssm describe-document --name Mongle-DeployV1 --document-version 1 --region ap-northeast-2 --query 'Document.{Version:DocumentVersion,Hash:Hash,HashType:HashType}'
```

반환된 숫자 `Version`과 `Hash`를 GitHub 변수로 사용한다. `HashType`은 Sha256이어야 한다. JSON 파일의 공백 정규화 방식은 AWS와 다를 수 있으므로 로컬 파일 해시를 문서 Hash로 추측하지 않는다. 이후 Document 갱신 시 버전·Hash를 함께 검토/변경한다. `$LATEST`/`$DEFAULT`를 배포 변수로 쓰지 않는다.

`ssm:GetCommandInvocation`의 리소스 `*`는 해당 결과 조회 API의 권한 모델 때문이다. 실행 권한은 정확한 문서와 인스턴스로 제한한다. AdministratorAccess, AWS-RunShellScript 전체 실행, IAM 변경 권한은 GitHub 배포 역할에 부여하지 않는다.

## 3. EC2 최초 설치 — SSM Session Manager

리뷰한 #76 커밋의 저장소 파일을 EC2 작업 디렉터리에 준비한다. 원격 develop의 임의 최신 파일을 실행하지 말고, 설치할 커밋으로 checkout한 뒤 내용을 대조한다. 위 renderer로 동일 `deploy.json`을 만들 수 있다.

SSM에서 **한 줄씩** 실행한다. 아래 상대 경로는 리뷰한 저장소 디렉터리 기준이다.

```bash
sudo install -d -o root -g root -m 755 /usr/local/libexec/mongle
sudo install -o root -g root -m 755 scripts/cd/deploy.py /usr/local/libexec/mongle/deploy-flyway.py
sudo install -o root -g root -m 600 build/cd-infra/deploy.json /etc/mongle/deploy.json
sudo install -d -o root -g root -m 700 /var/lib/mongle-deploy
/snap/bin/aws --version
sudo /snap/bin/aws sts get-caller-identity --query Arn --output text
sudo readlink -f /opt/mongle/current.jar
curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8080/actuator/health
```

관리자/Access Key를 EC2에 입력하지 않는다. `get-caller-identity`는 기존 EC2 역할인지 확인하는 조회이며 비밀값을 출력하지 않는다. 스크립트/config/state는 root 소유이고 앱 사용자는 수정할 수 없게 유지한다. `/opt/mongle`, 릴리스 JAR는 기존 mongle 소유를 유지한다.

설치 명령은 현재 JAR를 교체하거나 서비스를 재시작하지 않는다. `/etc/mongle/mongle.env`, 신뢰 저장소, Nginx, 인증서, systemd 유닛을 복사/덮어쓰지 않는다. OAuth 프로필을 이미 활성화했다면 새 JAR에 필요한 프론트 완료/실패 URL이 운영에 설정되어 있어야 한다. 이 작업이 그 환경값을 임의로 채우지는 않는다.

## 4. GitHub 저장소 변수

Settings → Secrets and variables → Actions → **Variables** → Repository variables.

| 이름 | 값 |
|---|---|
| MONGLE_CD_ROLE_ARN | arn:aws:iam::800417964658:role/mongle-github-deploy-role |
| MONGLE_CD_BUCKET | mongle-prod-deploy-800417964658 |
| MONGLE_CD_INSTANCE_ID | i-0ebfbdbacc2b3cc1b |
| MONGLE_CD_SSM_DOCUMENT_VERSION | 위 조회의 숫자 Version |
| MONGLE_CD_SSM_DOCUMENT_SHA256 | 위 조회의 Hash |
| MONGLE_CD_ENABLED | 모든 설치/권한 확인 후 `true` |

역할 ARN·버킷·인스턴스 식별자는 비밀 키가 아니므로 Variables를 사용한다. AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY를 생성하지 않는다. GitHub Environment나 추가 Required reviewers는 설정하지 않는다.

## 5. 최초 배포 확인

1. 로컬/CI의 Python 배포 시나리오·Java 테스트·bootJar가 통과하는지 확인한다.
2. IAM·버킷·문서·고정 서버 스크립트·변수 준비를 마친다. 켜지지 않은 `MONGLE_CD_ENABLED`는 CD를 skip한다.
3. PR을 develop에 머지하면 해당 push의 Server CI 성공 후 Server CD가 실행된다. 설정을 뒤늦게 완료했다면 **현재 develop HEAD의 push CI**를 재실행한다. 수동 workflow_dispatch CI는 배포 대상이 아니다.
4. CD 결과 아티팩트의 커밋/Command ID/status와 Systems Manager Run Command 실행 결과를 확인한다.
5. EC2 `sudo readlink -f /opt/mongle/current.jar`가 전체 SHA JAR인지, 내부·외부 health UP인지 확인한다. 환경 파일·기능 플래그가 유지됐는지와 DB 마이그레이션 결과/이력을 확인한다.
6. 실제 운영 JAR를 일부러 손상시키지 않는다. 자동 실패/복구는 테스트의 임시 디렉터리·모의 경계에서 먼저 검증한다. 실서버의 실패 주입은 사용자 영향과 점검 시간을 정한 후 별도 검증한다.

현재 Java 테스트는 기존 전체 suite이고, Python 테스트는 실제 파일/심볼릭 링크/상태 기록을 사용하며 AWS·systemd·health 경계는 모의 처리한다. 실제 OIDC AssumeRole·S3 업로드/다운로드·SSM 실행·운영 롤백 성공은 위 절차의 별도 증거가 필요하다.

## 장애와 수동 복구

자동 복구가 성공해도 해당 CD는 실패로 남는다. `/var/lib/mongle-deploy/state.json`의 highestSequence는 낮추지 않는다. 과거 CI 재실행으로 되돌리지 말고, 코드 revert를 develop에 새 커밋으로 머지해 검증/배포한다.

결과가 불명확하면 새 요청 전에 Command ID를 조회한다. SSM 명령이 진행 중인 동안 state/current.jar를 수동 수정하지 않는다. GitHub의 실행 취소만으로 원격 작업이 중단됐다고 판단하지 않는다.

긴급 수동 복구는 GitHub `MONGLE_CD_ENABLED=false` 후 진행 중 SSM이 종료됐는지 확인하고 한다. 다음은 **초기 정상 릴리스**로의 예시이며, 실제 복구 대상 파일의 존재/정상 여부를 먼저 확인한다.

```bash
sudo flock -n /var/lib/mongle-deploy/deploy.lock /bin/sh -c 'set -eu; test -f /opt/mongle/releases/mongle-f506c173.jar; ln -s /opt/mongle/releases/mongle-f506c173.jar /opt/mongle/.manual-recovery; mv -Tf /opt/mongle/.manual-recovery /opt/mongle/current.jar; systemctl restart mongle.service'
curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8080/actuator/health
curl --fail --silent --show-error --max-time 5 https://api.mongle.art/actuator/health
```

배포 잠금 획득 실패/기존 `.manual-recovery`가 있다면 원인을 확인한다. state를 무조건 삭제하지 않는다. pending이 남았다면 기록된 previous를 대조하고 복구 대상이 맞는지 확인한 뒤 정리한다. 과거 JAR와 새 DB 스키마의 호환성도 별도로 판단한다. DB 변경은 JAR 교체 전에 전용 프로세스로 실행한다. 앱 롤백은 DB를 되돌리지 않는다. 실패한 SQL/부분 DDL은 별도로 확인하고 자동 repair/clean으로 처리하지 않는다.

## 검증 명령

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
./gradlew --no-daemon test bootJar
actionlint .github/workflows/server-ci.yml .github/workflows/server-cd.yml
```

참고: [workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run), [GitHub OIDC](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), [SSM ENV_VAR](https://docs.aws.amazon.com/systems-manager/latest/userguide/documents-schemas-features.html), [SSM Run Command](https://docs.aws.amazon.com/systems-manager/latest/userguide/run-command.html).
