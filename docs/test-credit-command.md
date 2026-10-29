# 운영자용 테스트 크레딧 지급 (#91)

페이앱 실결제 없이 지정한 팀 테스트 계정에 무상 크레딧을 지급한다. 운영 권한이 있는 담당자가 EC2/SSM에서 실행하는 단발 JAR 명령이며 공개 HTTP API는 없다. 기본 비활성이다. 가입 시 3크레딧 자동 지급은 #93의 온보딩 로직이며, 이 명령은 가입 정책·결제 심사·판매 상품을 변경하지 않는다.

## 실행 계약

```text
java -jar <검증한 릴리스 JAR> --test-credit=preview --user-id=123 --amount=100 --request-key=qa-20261029-user123-01
java -jar <동일한 릴리스 JAR> --test-credit=grant --user-id=123 --amount=100 --request-key=qa-20261029-user123-01
```

- 네 인자를 모두 `이름=값` 형식으로 지정한다. 순서는 자유이며 중복·알 수 없는 인자·다른 실행 모드 혼합은 거절한다. `--test-credit` 오타도 일반 서버 실행으로 넘어가지 않는다.
- `preview`: 계정·온보딩·지급량·기존 지급·잔액 상한·보류를 읽기만 한다. 크레딧 계정·지급·원장 행을 만들지 않는다. 결과는 확인 시점의 상태이며 잔액 예약이나 실행 보장이 아니다.
- `grant`: 기존 `CreditService.grant`로 지급한다. 사용자 행 잠금 아래 지급 출처·잔액·원장을 한 트랜잭션에서 저장한다. 원장 실패는 전체 롤백한다.
- 계정 ID와 지급량은 양의 정수(long)다. 실험용으로 100크레딧을 사용할 수 있고 고정 지급량은 없다. 이미 있는 총 잔액까지 포함한 long 초과를 거절한다.
- 요청 키는 영문·숫자로 시작하는 ASCII 영문·숫자·점·밑줄·콜론·슬래시·하이픈 1~116자다. 내부에서는 `test-credit:`를 붙이고 `PROMOTION` 출처로 구분한다. 대소문자를 구별한다.
- 같은 키·계정·지급량의 재실행/동시 실행은 한 번만 지급한다. 다른 계정이나 다른 수량으로 재사용하면 충돌한다. 별도 추가 지급은 **새 키**를 사용한다. 키는 비밀번호가 아니며 이슈/운영 기록에 계정 ID·수량·키·지급 ID를 함께 보관한다.
- 결과는 JSON 한 줄이다. 성공은 종료 코드 0, 실패는 1이다. 현재 잔액의 available/reserved/total/spendingBlocked와 지급 ID를 확인한다. 원본 JDBC/SQL 오류나 비밀값은 출력하지 않는다.

| 상태 | 의미 |
| --- | --- |
| `WOULD_GRANT` | 읽기 검증 통과, 아직 지급하지 않음 |
| `GRANTED` | 지급 트랜잭션 커밋 완료 |
| `ALREADY_GRANTED` | 같은 지급이 이미 존재함. 추가 지급 없음 |

지급량은 추가할 수량이다. 가입 혜택3을 이미 받은 계정에100을 지급하면 잔액은103이다. 아래 성공 출력은 기존 잔액0인 계정의 예시다:

```json
{"event":"test-credit-completed","operation":"grant","status":"GRANTED","userId":123,"amount":100,"source":"PROMOTION","grantId":42,"balance":{"available":100,"reserved":0,"total":100,"spendingBlocked":false}}
```

## 필요한 환경과 권한

운영 앱의 기존 DML 접속값 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_TRUSTSTORE_URL`, `DB_TRUSTSTORE_PASSWORD`를 사용한다. `DB_USERNAME`은 `mongle_app`만 허용한다. 관리자·마이그레이션 계정, 마이그레이션 환경 파일은 사용하지 않는다. `ddl-auto=validate`에 해당하는 검증만 수행하며 Flyway·초기화 SQL·DDL을 실행하지 않는다.

| 환경변수 | 조건 |
| --- | --- |
| `MONGLE_TEST_CREDIT_ENABLED` | 기본 비활성. 해당 실행에서만 `true`로 지정 |
| `MONGLE_TEST_CREDIT_EXPECTED_HOST` | 실제 RDS 호스트 이름과 일치해야 함 |
| `MONGLE_TEST_CREDIT_EXPECTED_DATABASE` | `mongle` 등 대상 DB 이름과 일치해야 함 |
| `MONGLE_CREDIT_ENABLED` | 기존 운영 설정이 `true`여야 신규 지급 가능 |
| `MONGLE_CREDIT_EXPIRY_POLICY` | 기본/허용값 `NO_EXPIRY` |

JDBC URL은 `jdbc:mysql://호스트[:포트]/DB` 형식이다. URL의 인증정보·쿼리 옵션·fragment는 거절한다. TLS는 `VERIFY_IDENTITY`, 로컬 PKCS12 신뢰 저장소, 시스템 신뢰 저장소 fallback 비활성으로 고정한다. 연결 제한 5초·소켓 제한 15초·트랜잭션 제한 15초를 적용한다.

웹 서버·Spring Boot 전체 자동 설정·컴포넌트 스캔·OAuth·스케줄러·복구/정리 워커·외부 AI/S3/페이앱 클라이언트를 시작하지 않는다. 운영 프로필이나 AI 플래그가 켜져 있어도 이 명령에는 영향을 주지 않는다. JWT/OAuth/AI/결제 키는 필요 없으며 별도 운영자 API 키도 만들지 않는다. 실행 권한은 EC2/SSM 및 운영 비밀 파일 접근 권한으로 통제한다.

## EC2/SSM 실행 순서

1. 이 기능을 포함한 PR의 CI·머지·CD 성공 후 실행한다. 기존 `mongle.service`는 중지하지 않는다.
2. 실제 테스트할 카카오 계정으로 로그인·닉네임 등록을 완료한다. 로그인 응답 또는 인증된 `GET /api/v1/users/me`의 본인 ID를 확인한다. 이메일로 추측하거나 다른 계정 ID를 넣지 않는다.
3. 인프라 담당자는 기존 운영 파일에서 `MONGLE_CREDIT_ENABLED=true`, 유효기간 `NO_EXPIRY`를 확인한다. 변경이 필요하면 정상 운영 설정 절차로 반영하고 앱도 재시작한다. 명령만 강제로 활성화해서 앱의 과금 설정과 다르게 운영하지 않는다. 이 작업을 위해 PayApp 결제를 활성화할 필요는 없다.
4. `sudo readlink -f /opt/mongle/current.jar`로 검증한 릴리스의 절대 경로를 확인한다. 아래 `<확인한 릴리스 JAR 경로>`와 계정 ID·요청 키를 바꾼다. 프리뷰와 지급에 같은 JAR·입력을 사용한다. 아래 `mongle-db.example.invalid`는 가상 호스트이므로 AWS RDS 콘솔의 연결 정보 또는 보호된 운영 설정에서 실제 호스트를 확인해 교체한다. 환경 파일 전체를 출력하지 않는다.

```bash
sudo /usr/bin/systemd-run --quiet --wait --pipe --collect \
  --service-type=exec --unit=mongle-test-credit-preview.service \
  --property=User=mongle --property=Group=mongle \
  --property=WorkingDirectory=/opt/mongle \
  --property=EnvironmentFile=/etc/mongle/mongle.env \
  --property=NoNewPrivileges=true --property=PrivateTmp=true \
  --property=ProtectSystem=strict --property=ProtectHome=true \
  --property=RuntimeMaxSec=90 --property=TimeoutStopSec=15 \
  --setenv=MONGLE_TEST_CREDIT_ENABLED=true \
  --setenv=MONGLE_TEST_CREDIT_EXPECTED_HOST=mongle-db.example.invalid \
  --setenv=MONGLE_TEST_CREDIT_EXPECTED_DATABASE=mongle \
  /usr/bin/java -Xms32m -Xmx256m -jar <확인한 릴리스 JAR 경로> \
  --test-credit=preview --user-id=123 --amount=100 --request-key=qa-20261029-user123-01
```

5. `WOULD_GRANT`와 대상 ID·수량·기존 잔액을 확인한 후, 같은 명령에서 유닛 이름만 `mongle-test-credit-grant.service`, 마지막 실행 모드만 `--test-credit=grant`로 바꿔 실행한다. 기존 지급이면 `ALREADY_GRANTED`를 확인하고 추가 실행하지 않는다.
6. 앱에서 잔액·이용 내역을 다시 조회한다. 운영 환경 파일을 출력하거나 셸에서 `source`하지 않는다. 비밀값을 인자·GitHub·프론트 환경변수·스크린샷에 복사하지 않는다. 전용 명령 플래그를 기존 장기 실행 서비스에 추가할 필요도 없다.

프로세스 종료·시간 초과·응답 유실이면 지급이 커밋됐을 수 있다. **같은 요청 키**로 preview/재실행하여 확인한다. 확인 전에 다른 키로 반복 지급하지 않는다. systemd 실행 로그에는 계정 ID·금액·지급 ID 등 운영 정보가 남으므로 접근을 기존 운영 권한으로 제한한다.

## 프론트와 실제 이미지 검증

동일 카카오 테스트 계정으로 아래 기존 API를 사용한다. 테스트 지급용 프론트 버튼이나 별도 로그인 우회는 없다.

- `GET /api/v1/credits/balance`: 지급 후 available 증가·reserved 0 확인.
- `GET /api/v1/credits/transactions`: `GRANT`/`PROMOTION` 내역 확인. 요청 키·결제 키는 공개 API에 노출되지 않는다.
- 개별 꿈 이미지: 첫 성공 3 차감 → 첫 재생성 성공 무료 → 그다음부터 성공마다 1 차감. 실패하면 예약을 해제하고 무료 기회도 소비하지 않는다.
- 세계관 이미지: 완성본에 서로 다른 꿈 2개 이상을 준비한 뒤 같은 과금 흐름을 검증한다. 0~1개는 기존 이미지를 보존하고 새 생성·재생성을 거절한다.
- 스타일/원문/세계관 구성을 바꿔도 같은 꿈/세계관의 무료 이력은 초기화하지 않는다. 기존 성공 결과 재사용·조회·다운로드에는 차감이 없다.
- OpenAI·S3·필요한 워커의 실제 준비 상태는 인프라 담당자와 따로 확인한다. 크레딧 지급 성공은 외부 이미지 생성 성공을 뜻하지 않는다. 검증 결과는 대상 종류·시도 상태·잔액·예약/확정/해제 내역으로 남기고 꿈 원문·API 키는 공유하지 않는다.

## 실패 처리

- `INVALID_ARGUMENTS`: 빠진/중복/혼합 인자, 잘못된 수량·요청 키. DB 연결 전 거절.
- `OPERATOR_COMMAND_DISABLED`: 전용 실행 허용값이 없음/false. DB 연결 전 거절.
- `INVALID_CONFIGURATION`: 대상 호스트/DB/앱 계정/TLS/필수 환경 설정 불일치. DB 연결 전 거절.
- `CREDIT_503_1`: 크레딧 기능 비활성. 기본 운영 정책을 우회하지 않는다.
- 계정 없음·온보딩 미완료·수량 초과·기존 키 충돌: 기존 크레딧/계정 오류 코드 반환.
- `CREDIT_409_5`: 결제 사용 보류 계정은 이 도구의 신규 지급도 거절한다. 보류를 지우거나 해제하지 않는다.
- `CREDIT_WRITE_CONFLICT`: DB 무결성 충돌. 트랜잭션은 롤백한다. 같은 키로 사전 확인한 뒤 원인을 확인한다.
- `TEST_CREDIT_FAILED`: DB/TLS/스키마 등 기타 실패. 원본 오류는 비밀값 보호를 위해 숨긴다. 인프라 담당자가 설정·연결·배포 버전·일반 서버 상태를 점검한다.

DB 구조·Flyway 버전·공개 API 변경은 없다. 이미 적용한 V1/V3/V4와 운영 이력을 수정하지 않는다.

## 검증 기록

2026-10-29: 일반 테스트 787개, 임시 MySQL 8.4.11 테스트 37개, 배포 스크립트 테스트 44개와 bootJar 빌드 통과. 동일 지급 계약을 H2/실제 MySQL에서 검증하며 실제 앱 DML 계정의 TLS·DDL 거부도 확인한다. 실행 JAR를 별도 JVM으로 띄워 preview 무변경, grant/replay/conflict/disabled, 잘못된 DB 인증 시 정제된 오류, 독립 JVM 두 개의 동시 중복 지급을 확인했다. 운영 DB 지급·OpenAI/S3 호출은 실행하지 않았다.
