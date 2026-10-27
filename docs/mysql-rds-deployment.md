# MySQL 8.4 / EC2 최초 배포

EC2에서 Java 21 JAR와 RDS MySQL 8.4를 사용한다. Docker는 필요 없다. 앱은 `mysql` 프로필의 `ddl-auto=validate`로 실행하며, 후속 스키마 변경은 [Flyway/CD 도입 절차](database-migrations.md)에 따라 전용 계정으로 적용한다. 아래 수동 스키마 절차는 최초 배포/과거 호환성 설명이며 이미 운영 중인 DB에 초기화 SQL을 재실행하는 안내가 아니다. 앱 계정에는 DB의 SELECT/INSERT/UPDATE/DELETE와 REQUIRE SSL만 부여한다.

## JDBC TLS 설정

`application-mysql.yaml`은 아래 환경변수를 Hikari/Connector-J에 직접 연결한다. `sslMode=VERIFY_IDENTITY`, `fallbackToSystemTrustStore=false`, PKCS12 형식을 사용한다. CLI에서 PEM을 지정한 것으로 Java 연결까지 설정되는 것은 아니다.

```dotenv
SPRING_PROFILES_ACTIVE=mysql
DB_URL=jdbc:mysql://<RDS-endpoint>:3306/mongle
DB_USERNAME=mongle_app
DB_PASSWORD=<secret>
DB_TRUSTSTORE_URL=file:/etc/mongle/rds-truststore.p12
DB_TRUSTSTORE_PASSWORD=<secret>
MONGLE_AUTH_SECRET=<Base64-encoded-random-32-or-more-bytes>
```

위 값은 예시다. 비밀값은 Git에 넣지 않고 접근을 제한한 환경 파일/비밀 관리로 전달한다. URL에는 비밀번호나 다른 TLS 옵션을 중복 지정하지 않는다. `DB_URL`의 호스트는 인증서와 일치하는 RDS 엔드포인트를 사용한다. API 도메인, IP로 대체하거나 `useSSL=false`, `allowPublicKeyRetrieval=true`로 오류를 우회하지 않는다. `local` 프로필은 함께 켜지 않는다.

AWS 공식 RDS 인증서 번들에서 해당 RDS의 루트 CA를 확인해 PKCS12에 등록한다. 중간 인증서는 신뢰 앵커로 넣지 않는다. 다음 명령은 공개 루트 인증서 파일을 준비한 뒤 실행하며, 신뢰 저장소 비밀번호는 대화형 입력으로 받는다.

```bash
keytool -importcert -alias rds-root -file /path/to/verified-rds-root.pem \
  -keystore /etc/mongle/rds-truststore.p12 -storetype PKCS12
```

인증서 내용·지문·유효기간을 확인한 후 등록한다. CA 교체 계획에 따라 필요한 루트들을 별도 별칭으로 추가한다. 신뢰 저장소는 앱 실행 계정이 읽을 수 있어야 한다. DB/신뢰 저장소 비밀번호를 명령행, JDBC URL, `JAVA_TOOL_OPTIONS`에 넣지 않는다.

참고: [Connector/J 보안 설정](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-security.html), [AWS RDS TLS와 CA](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html).

## 현재 29개 테이블에서 최신 코드로 전환

기존 DB는 크레딧/결제/이미지 예약까지 적용한 29개 테이블을 기준으로 한다. `usage`가 이미 실제 컬럼명으로 존재하면 이번 예약어 수정 때문에 ALTER할 필요가 없다. Java 필드명·API 필드명도 유지한다.

1. 배포할 커밋과 현재 스키마를 확인한다. 읽기 전용 점검은 `db/checks/mysql84-preflight.sql`을 사용한다. 비밀번호는 `mysql --password`의 대화형 입력이나 권한을 제한한 옵션 파일로 전달한다.
2. 앱과 모든 워커를 중지하고 DB 백업/복구 지점을 확보한다. 사전 점검 결과가 예상과 다르면 SQL을 실행하기 전에 원인을 확인한다.
3. 정확히 해당 상태의 29개 테이블이며 두 세계관 테이블이 모두 없다면, 관리자 계정으로 `db/migrations/20261026-world-composition.sql`만 적용한다. `IF NOT EXISTS`는 기존 테이블의 컬럼·인덱스를 고쳐주지 않으므로 부분 적용 흔적이 있다면 먼저 비교한다.
4. 31개 테이블·컬럼·인덱스를 다시 점검하고, 앱 계정과 `mysql` 프로필로 기동해 Hibernate 검증 통과를 확인한다. 신규 테이블에도 기존 DB 단위 DML 권한이 적용된다.

기존 DB에 초기 SQL 전체나 과거 마이그레이션 전체를 다시 실행하지 않는다. 초기 SQL은 빈 DB를 처음 만들 때만 사용한다. 테이블/DB를 삭제하거나 `create`, `create-drop`, `update`로 운영 스키마를 자동 변경하지 않는다.

## 외부 연동 전 첫 기동

```dotenv
MONGLE_DREAM_GENERATION_ENABLED=false
MONGLE_WORLD_COMPOSITION_ENABLED=false
MONGLE_WORLD_COMPOSITION_WORKER_ENABLED=false
MONGLE_IMAGE_RECOVERY_ENABLED=false
MONGLE_IMAGE_OPENAI_ENABLED=false
MONGLE_IMAGE_S3_ENABLED=false
MONGLE_IMAGE_CLEANUP_ENABLED=false
MONGLE_PAYMENT_ENABLED=false
MONGLE_PAYAPP_ENABLED=false
MONGLE_CREDIT_ENABLED=false
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8080
SERVER_FORWARD_HEADERS_STRATEGY=framework
```

첫 확인용 설정이다. 기존 예약이 있는 서비스에서 복구 워커를 장기간 끄는 운영 정책으로 사용하지 않는다. OAuth 준비 전에는 `oauth` 프로필을 추가하지 않는다. `java -jar <배포-JAR>`로 기동하고 스키마 검증 성공·`/actuator/health` 응답을 확인한다. Swagger를 공유할 경우 명시적으로 활성화하고 접근 범위를 정한다. 운영 기본값은 비활성이다.

Nginx는 Host와 X-Forwarded-Proto를 실제 요청 기준으로 덮어써서 전달하고, 앱 포트는 외부에 열지 않는다. HTTPS·OAuth redirect·CORS·쿠키 정책을 맞춘 뒤 각 연동을 순서대로 켠다. DB CLI의 `SHOW SESSION STATUS LIKE 'Ssl_cipher'`는 그 CLI 세션만 증명하므로 앱의 TLS 검증을 대신하지 않는다.

## 로컬 실제 MySQL 회귀

Java 21, Python 3, 네이티브 MySQL 8.4.x(`mysqld`, `mysql`), OpenSSL, `keytool`이 PATH에 있어야 한다.

```bash
./gradlew --no-daemon test bootJar
python3 scripts/mysql84-test.py
```

별도 로컬 서버·임시 데이터 디렉터리·테스트 CA·최소 DML 계정을 만든다. 외부 DB 주소를 입력받지 않고 기존 MySQL 서비스/RDS에 접속하지 않는다. 새 초기 스키마와 기존 29개→33개 테이블 전환의 컬럼·인덱스 일치 및 기존 행 보존을 검사한 뒤, `mysqlTest`에서 크레딧 예약 동시성/중복/확정/해제/롤백과 결제 조기 콜백/중복 지급 방지를 실행한다. Flyway 기준 등록·최신 스키마/기존 앱 호환성·부분 DDL/체크섬 실패와 패키징된 전용 실행 모드도 검증한다. MySQL 프로필의 실제 TLS 설정을 사용하며, 신뢰하지 않는 저장소의 연결 실패도 검증한다.

종료 시 임시 서버를 중지하고 데이터·개인 키·신뢰 저장소·비밀번호 파일을 삭제한다. 결과는 `build/reports/tests/mysqlTest/index.html`에 남는다. 강제 종료(SIGKILL)로 정리되지 못한 경우에는 해당 `mongle84-*` 임시 인스턴스만 확인해 정리한다. 일반 `./gradlew test`는 기존 H2 회귀를 유지하고 MySQL 설치를 요구하지 않는다.

## 과거 세계관 이미지 스키마 추가 (#71)

Flyway 도입 전33개 업무 테이블에 도달하기 위한 과거 안내다. 이미33개 테이블이 있거나 Flyway 이력을 도입한 DB에서는 이 SQL을 다시 실행하지 않는다.

세계관 완성본까지 적용된 31개 테이블에는 앱/워커 중지·백업 후 `db/migrations/20261026-world-images.sql`을 추가 적용한다. 총 33개 테이블과 Hibernate validate를 확인한다. 29개 테이블에서 출발하면 앞의 세계관 구성 SQL을 먼저 적용한다. S3 IAM 정책에도 world-images/* 경로를 추가한다. [세부 설정과 생성 정책](world-image.md)을 따른다.
카카오 로그인 활성화 시 제공사별 설정과 프론트 복귀 URL 등 추가 필수 설정은 [카카오 웹 로그인 안내](kakao-oauth-web.md)를 따른다.
