# DB 마이그레이션과 최초 운영 도입

## 실행 계약

일반 앱은 기존 DML 계정과 `ddl-auto=validate`로 실행한다. Flyway 자동 설정은 비활성이고 앱 프로필/환경변수로 마이그레이션을 켜지 않는다. CI가 검증한 동일 JAR를 별도 프로세스에서 다음 명령 중 하나로 실행한다.

```text
java -jar <검증한-JAR> --db-migration=info
java -jar <검증한-JAR> --db-migration=baseline
java -jar <검증한-JAR> --db-migration=migrate
```

이 모드는 HTTP·JPA·OAuth·업무 워커를 시작하지 않는다. 인자는 위 하나만 허용한다. `clean`, `repair`, `undo`, SQL/비밀번호 인자는 거절한다. 앱의 `DB_*` 값으로 대체하지 않으며 `docs/migration.env.example`의 별도 설정을 사용한다. URL에 사용자 정보·쿼리·fragment를 넣을 수 없다. 명시한 호스트/DB와 실제 URL을 대조하고 MySQL8.4 및 실제 접속 catalog를 확인한다. TLS는 `VERIFY_IDENTITY`와 PKCS12를 강제한다.

출력은 상태/오류 코드만 포함한 JSON이다. Flyway/JDBC 원문 오류·SQL·암호는 전달하지 않는다. 실패 시 종료 코드는1이며 CD는 이를 성공으로 처리하지 않는다.

## 기준 상태와 SQL 관리

- `db/migration/V1__mongle_baseline.sql`: 신규 빈 DB의33개 업무 테이블 생성용이다. 이미 운영 중인 DB에 수동 재실행하지 않는다.
- `db/baseline/v1-schema.json`: MySQL8.4에서 생성한 테이블·컬럼·인덱스·외래키·CHECK 제약 메타데이터다. 행 수·데이터·AUTO_INCREMENT 카운터·환경별 schema 이름은 포함하지 않는다.
- `baseline`은 메타데이터가 정확히 일치하고 이력 테이블이 없는 경우에만 버전1을 등록한다. `info`의 `matchesBaseline`과 `schemaSha256`으로 먼저 확인한다.33개라는 개수만 같아도 구조가 다르면 거절한다.
- 최초 도입 준비 중 이미 baseline을 수행했다면 이력을 지우거나 재등록하지 않고 새 검증 JAR의 info로 기준 구조를 재확인한다.
- `flyway_schema_history`는 관리 테이블이므로 업무 테이블 수에서 제외한다. 최초 등록 후에는 기존33개+관리1개가 된다.
- 적용 후 V1/기준 메타데이터를 수정하지 않는다. 새 변경은 `V2__설명.sql`, `V3__설명.sql` 순서로 추가한다. 기존 날짜별 `db/migrations/` 파일은 과거 참고 자료이며 Flyway 자동 검색 대상이 아니다.
- `schema-initial.sql`은 과거 호환성 검증에도 사용되는 기준 자료다. 운영용 최신 DB 생성 경로는 Flyway다. 기존 DB를 초기화하거나 과거 SQL을 일괄 재실행하지 않는다.
- 버전/파일명/체크섬을 검증하며 누락된 적용 이력이나 알려지지 않은 미래 버전을 묵인하지 않는다. 자동 baseline/clean/repair/out-of-order 실행은 허용하지 않는다.

## 코드 검증

이미지 무료 재생성 정책은 `V3__image_regeneration_policy.sql`로 적용한다. V3는 꿈/세계관 이미지에 무료 재생성 사용 여부와 현재 무료 시도 여부 컬럼을 추가한다. 기존 행의 기본값은 false이며 기존 성공 파일은 보존하고, 도입 이후 무료 재생성 1회를 제공한다. 기존 이미지 작업을 종료/만료 복구한 뒤 새 버전을 배포한다. V1 기준 등록은 종전 구조에서 먼저 수행하며, 최신 스키마를 V1 메타데이터로 다시 baseline하지 않는다. #86의 이미지 정책 V3가 먼저 머지·배포되었으므로 #85 종합 해석은 V4__recent_interpretations.sql로 적용한다. 미배포 V2를 뒤늦게 추가하지 않는다. 통합 배포 JAR에는 기존 V1·V3와 신규 V4 SQL을 포함하며, 기존 이력 수정이나 out-of-order 활성화 없이 V3→V4로 진행한다. [정책과 배포 경계](image-generation-policy.md)를 참고한다.

```sh
./gradlew --no-daemon test bootJar
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 scripts/mysql84-test.py
```

MySQL 검증은 Java21/OpenSSL/keytool과 네이티브 MySQL8.4 `mysqld/mysql`이 PATH에 있어야 한다. 임시 loopback DB와 무작위 비밀번호·CA를 생성하고 종료 시 제거한다. 운영 DB에 접속하지 않는다. Gradle 작업은 같은 build 폴더에서 동시에 실행하지 않는다.

GitHub CI는 공식 MySQL8.4.11 최소 바이너리를 SHA256 고정 검증 후 runner 임시 폴더에 설치한다. Docker/시스템 DB는 사용하지 않는다. 신규 V1 생성, 기존 DB baseline, 데이터 보존, 후속 버전, 부분 DDL 실패, 체크섬 변경, 권한/TLS 및 마이그레이션 후 기존 엔티티의 기동·조회·저장을 검증한다. 이 단계가 실패하면 배포 아티팩트를 게시하지 않는다.

## EC2/CD의 실행 경계

CD는 기존 S3·OIDC·고정 SSM 문서를 사용한다. 새 필수 SSM 파라미터 `MigrationProtocol=flyway-v1`과 별도 고정 파일 `/usr/local/libexec/mongle/deploy-flyway.py`를 사용하므로 기존 문서 버전을 그대로 쓰면 실패하며 기존 배포기로 우회하지 않는다. 문서 이름은 `Mongle-DeployV1`을 유지하되 **숫자 버전과 AWS가 반환한 SHA256**을 함께 갱신한다.

새 배포기는 다운로드/해시 검증→기존 앱 health→배포 순서 기록→DB migrate→링크 전환→재시작/내외부 health 순서로 실행한다. 기존 배포 잠금과 실행 순서 검증을 유지한다. SQL이 실패하면 링크/서비스를 교체하지 않고 `state.json`의 `migration.status=failed`를 남긴다. 마이그레이션 성공 후 앱이 실패하면 이전 JAR만 복구하고 적용된 DB 상태는 유지한다.

DB 프로세스는 OS `mongle-migrate`, DB `mongle_migrator`를 사용한다. 앱 계정/그룹과 공유하지 않는다. 앱 소유 releases의 JAR를 직접 DDL 권한으로 실행하지 않고, 해시를 다시 확인한 root 소유 `/var/lib/mongle-migration/` 복사본을 실행한다. 별도 환경 파일과 제한된 systemd 임시 서비스를 사용하며 기존 앱 비밀 파일은 접근 경로에서 제외한다.

프로세스는 최대240초, systemd 종료 대기15초, 클라이언트 대기270초로 제한한다. JDBC 소켓 제한은250초로 두어 정상 CD에서는 전체 프로세스 제한이 먼저 작동하게 한다. JDBC 제한을 다시60초처럼 짧게 줄이지 않으며 세 시간 예산을 함께 확인한다. 실패/중단 시 고정 마이그레이션 서비스 종료를 요청한다. 종료 중인 DDL이 서버에서 끝났는지는 이력/스키마를 확인해야 하며, 자동 repair나 DB 되돌리기로 성공을 위장하지 않는다. MySQL DDL은 트랜잭션 롤백을 보장하지 않는다.

## 최초 운영 적용 순서 — 인프라 채팅

### 1. 현재 서비스와 백업 확인

운영 DB의 실제33개 테이블이 최신 V1과 일치하는지 메타데이터를 확인하고 RDS 복구 지점/스냅샷을 확보한다. 코드 CI가 운영 데이터 구조까지 확인한 것은 아니다. 운영 스키마 조회 자료가 없으면 `info`/읽기 전용 사전 점검부터 수행한다.

최초 전환 중에는 `MONGLE_CD_ENABLED=false`로 배포를 잠시 멈추고 기존 JAR와 health를 유지한다. #81 코드 준비·계정·기준 등록·SSM 갱신을 마친 뒤 다시 켠다. 새 JAR에 포함된 Flyway만 보고 준비 전 머지하지 않는다.

### 2. 전용 계정과 파일 준비

기존 관리자 TLS 연결에서 `docs/mysql-migrator-account.example.sql`을 검토해 전용 DB 계정을 만든다. EC2 private IP를 확인하고, 실제 비밀번호는 서버에서만 입력한다. 기존 앱 계정에 DDL/관리자 권한을 추가하지 않는다.

EC2에서 OS 사용자/그룹과 디렉터리를 준비한다. 기존 사용자가 있다면 UID/GID가 앱과 다른지 먼저 확인하고 재생성하지 않는다.

```sh
getent passwd mongle-migrate
getent group mongle-migrate
```

둘 다 없을 때만 다음을 실행한다.

```sh
sudo useradd --system --user-group --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin mongle-migrate
sudo install -d -o root -g mongle-migrate -m 750 /etc/mongle-migration /var/lib/mongle-migration
sudo install -o root -g mongle-migrate -m 640 /etc/mongle/rds-truststore.p12 /etc/mongle-migration/rds-truststore.p12
```

새 환경 파일이 없는지 확인한 뒤 root 소유0600으로 만들고 `sudoedit /etc/mongle-migration/migration.env`로 예시의 값을 채운다. 예를 들어 파일이 없을 때만 `sudo install -o root -g root -m 600 /dev/null /etc/mongle-migration/migration.env`를 실행한다. 기존 파일에는 이 명령을 실행하지 않는다. 파일 내용을 로그/채팅에 출력하지 않는다. 신뢰 저장소 사본은 기존 저장소 암호를 사용하되 DB 비밀번호는 새로운 전용 계정 값이다. 기존 `/etc/mongle/mongle.env`와 원본 신뢰 저장소는 변경하지 않는다.

### 3. CI JAR로 info/baseline 실행

성공한 #81 CI의 아티팩트에서 JAR·SHA256SUMS·build-metadata.json을 받는다. 커밋과 체크섬을 해당 CI 실행의 값과 대조한다. 별도 임의 JAR를 사용하지 않는다. GitHub CI의 수동 실행 결과는 자동 배포 대상이 아니지만 최초 기준 등록용으로 수동 검토 후 사용할 수 있다.

S3 콘솔에서 JAR만 기존 배포 버킷의 `releases/bootstrap/<CI-commit>/`에 올린 뒤 EC2 역할로 내려받는다. `releases/` 밖에 올리면 EC2의 제한된 읽기 권한으로 받을 수 없다. root 소유 cache에 `root:mongle-migrate`,0640으로 설치하고 SHA256을 다시 확인한다. 이 과정은 current.jar를 바꾸거나 앱을 재시작하지 않는다.

아래 `<검증한-JAR-절대경로>`는 cache 안의 JAR 경로로 바꾼다. 배포와 같은 잠금 아래에서 먼저 조회한다.

```sh
sudo flock -n /var/lib/mongle-deploy/deploy.lock /usr/bin/systemd-run --quiet --wait --pipe --collect --service-type=exec --unit=mongle-db-migration.service --property=User=mongle-migrate --property=Group=mongle-migrate --property=EnvironmentFile=/etc/mongle-migration/migration.env --property=RuntimeMaxSec=240 --property=TimeoutStopSec=15 --property=NoNewPrivileges=true --property=PrivateTmp=true --property=ProtectSystem=strict --property=ProtectHome=true --property=InaccessiblePaths=/etc/mongle /usr/bin/java -Xms32m -Xmx256m -jar <검증한-JAR-절대경로> --db-migration=info
```

`matchesBaseline=true`, `historyExists=false`, 업무 테이블33개와 대상 DB/접속 정보를 확인한 뒤 **같은 명령의 마지막 인자만** `--db-migration=baseline`으로 바꿔 명시적으로 등록한다. 다시 `--db-migration=info`로 이력 유무를 확인한다. 이름이 잘못된 DB·구조가 다른 DB·이미 이력이 있는 DB를 강제로 승인하지 않는다.

### 4. 새 고정 배포기/SSM 설치

검증한 #81 커밋에서 renderer를 실행한다. root 설치 파일은 Git의 해당 커밋 내용을 대조한다.

```sh
python3 scripts/cd/render_infra.py --bucket mongle-prod-deploy-800417964658 --instance-id i-0ebfbdbacc2b3cc1b
sudo install -o root -g root -m 755 scripts/cd/deploy.py /usr/local/libexec/mongle/deploy-flyway.py
sudo install -o root -g root -m 600 build/cd-infra/deploy.json /etc/mongle/deploy.json
```

`ssm-deploy.json`을 `Mongle-DeployV1`의 새 숫자 버전으로 등록한다. AWS describe-document가 반환한 DocumentVersion·HashType=Sha256·Hash를 대조하고 GitHub 변수 `MONGLE_CD_SSM_DOCUMENT_VERSION`, `MONGLE_CD_SSM_DOCUMENT_SHA256`을 갱신한다. 파일 내용을 로컬 해시한 값을 AWS 문서 Hash로 추측하지 않는다. `$LATEST`/`$DEFAULT`는 사용하지 않는다. 기존 IAM 역할 범위/문서 이름/인스턴스/환경 파일/HTTPS를 유지한다.

GitHub의 `MONGLE_CD_ENABLED=true`는 계정·baseline·설치·버전/해시 확인을 마친 뒤 복구한다. PR 머지 후 develop CI/CD의 `database-migrated`·`deployment-succeeded` 및 실제 health를 확인한다. 이번 V1 도입은 업무 스키마를 추가 변경하지 않으며 이력 확인/미적용 후속 SQL 처리만 수행한다.

## 실패 처리와 다음 배포 규칙

- 구조 불일치: info와 메타데이터를 대조한다. 자동 baseline/clean/기존 초기화 SQL 재실행으로 해결하지 않는다.
- 이력 체크섬 불일치: 적용된 SQL을 임의 수정한 것인지 확인한다. repair로 자동 덮어쓰지 않는다.
- 부분 DDL 실패/시간 초과: 이전 앱 링크를 유지하고 이력의 version/success 및 실제 객체를 확인한다. 백업/실제 변경을 근거로 별도 복구를 정한 뒤 재시도한다.
- 앱 롤백: DB는 최신 확장 상태로 남는다. 컬럼/테이블 삭제·이름 변경·즉시 NOT NULL 전환 등 이전 앱을 깨는 변경은 한 번에 넣지 않는다. expand→앱 전환→후속 정리로 나눈다.
- 코드 revert에서도 이미 운영에 적용된 `V*` SQL 파일은 삭제하거나 수정하지 않는다. 앱 변경만 되돌리고 기존 SQL 원본을 보존한다. 스키마 보정이 필요하면 후속 버전 SQL로 추가한다. 적용된 SQL이 JAR에서 누락되면 이후 migrate는 검증에 실패한다.
- SQL과 새 JAR는 같은 검증 아티팩트여야 한다. 배포 중 서버에서 최신 develop의 SQL을 별도로 가져오지 않는다.

## 공식 근거

- https://docs.spring.io/spring-boot/how-to/data-initialization.html
- https://documentation.red-gate.com/flyway/reference/commands/baseline
- https://documentation.red-gate.com/flyway/flyway-concepts/migrations/migration-transaction-handling
- https://dev.mysql.com/doc/refman/8.4/en/binary-installation.html
