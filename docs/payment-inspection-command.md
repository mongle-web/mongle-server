# 운영 결제·크레딧 대조 조회 (#117)

지정한 주문 UUID의 내부 DB 기록을 **읽기만 하는** 단발 JAR 명령이다. 주문·지급 출처·크레딧 잔액·원장·예약 배분·사용 보류·통보 금액을 같은 시점의 스냅샷으로 대조한다. 웹 서버·워커·외부 AI/S3/페이앱 클라이언트를 시작하지 않는다. 공개 API나 Swagger 경로는 추가하지 않는다.

**페이앱 거래 조회가 아니다.** `CONSISTENT`도 실제 결제·환불 완료, 환불 가능 금액, 보류 해제 허가를 뜻하지 않는다. 페이앱 관리자 기록과 계약·환불 기준은 운영 담당자가 별도로 확인한다. 지급·회수·취소·환불·예약 해제·보류 해제·주문 상태 변경은 이 명령에 없다.

## 실행 계약

```text
java -jar <검증한 릴리스 JAR> --payment-inspect=order --order-id=<주문 UUID>
```

두 인자를 모두 `이름=값` 형식으로 전달한다. 순서는 자유다. 주문 ID는 API에서 받은 소문자 정규 UUID(예: `8b326cda-d098-4c31-9d7d-74cdd71a0a44`)를 그대로 사용한다. DB 내부 숫자 ID·회원 ID·페이앱 거래번호를 넣지 않는다. 누락·중복·다른 명령 혼합·오타는 DB 연결 전에 거절한다. `--payment-inspect`로 시작하는 오타도 서버 실행으로 넘어가지 않는다.

결과는 JSON 한 줄이다. **종료 코드 0은 조회 완료**이며 결제 성공 판정이 아니다. 미확정·검토 필요도 조회가 끝났으면 0이다. 인자/설정 오류·없는 주문·DB 오류·시간 초과는 종료 코드 1과 정제된 코드만 출력한다.

| `status` | 의미 |
| --- | --- |
| `CONSISTENT` | 이번 조회의 내부 대조 항목에서 불일치가 없음. 외부 거래 상태와 지출 가능 여부는 별도 확인 |
| `UNCONFIRMED` | 불일치는 없지만 `REQUESTING`/`READY`/`PENDING`으로 결제 결과 미확정 |
| `REVIEW_REQUIRED` | 대조 불일치 또는 `UNKNOWN`/`PAYMENT_REVIEW`/알 수 없는 상태. 자동 복구하지 않음 |

`findings`가 비어 있어도 `PAYMENT_REVIEW`는 검토 필요다. 취소 통보에 따라 정상적으로 보류된 경우에도 사람이 실제 거래와 처리 방침을 확인해야 한다. 기한이 지난 `REQUESTING`은 기존 주문 API와 같이 `effectiveState=UNKNOWN`으로 **표시만** 하며 저장된 상태는 변경하지 않는다.

## 출력 필드

| 필드 | 범위와 해석 |
| --- | --- |
| `scope` | 항상 `INTERNAL_DATABASE_ONLY` |
| `observedAt` | 첫 주문 조회 직후 관찰 시각(UTC). 이후 대조 쿼리는 같은 DB 스냅샷 사용 |
| `order` | 주문 UUID·회원 ID·상품 ID·주문 당시 금액/통화/지급량·저장/유효 상태·연결된 지급 ID |
| `purchase` | 해당 회원과 `PAYMENT` 출처·주문 키/해시가 일치하는 지급. 없거나 중복이면 null. amount/available/reserved/consumed/paymentHold |
| `account` | 해당 회원 전체 잔액. present/available/reserved/holdCount/spendingBlocked, 전체 지급 합계 grantAvailable/grantReserved와 전체 원장 합계 ledgerAvailable/ledgerReserved |
| `purchaseLedger` | 해당 지급의 GRANT 수·지급량과 PAYMENT_HOLD 수. 구매와 연결된 지급이 없으면 0 |
| `purchaseAllocations` | 해당 지급에서만 배분된 reserved/consumed/released/expiredReserved 크레딧. 구매 지급이 없으면 0 |
| `notifications` | 알려진 상태 코드별 건수와 최소/최대 통보 금액. 알 수 없는 코드는 -1로 합침 |
| `findings` | 연결·소유권·출처·금액·원장·예약·통보 불일치 코드 |
| `notices` | 다른 주문의 보류, 만료됐지만 해제되지 않은 예약 등 관찰 사항 |

구매분과 계정 전체를 구분한다. 예를 들어 무상3+구매10 중 이미지에5를 예약해 무상3/구매2가 배분됐다면, 계정 reserved=5지만 구매 reserved=2다. 전체 예약 원장5를 해당 구매 사용량으로 계산하지 않는다. `released`는 과거 해제된 배분 합계로 현재 잔액에 더하지 않는다. 여러 번 재예약/해제하면 원래 지급량보다 클 수 있다.

`purchase.paymentHold=false`여도 **다른 구매의 보류** 때문에 `account.spendingBlocked=true`일 수 있다. 보류 상태에서는 기존 앱의 사용 제한이 유지된다. 반대로 false도 잔액·기능 활성화·이미지 구성 등 실행 조건 전체를 보장하지 않는다. 만료 예약은 남아 있는 양을 표시할 뿐 복구 워커를 대신하지 않는다.

통보 건수·최소/최대 금액을 **합산 환불액으로 해석하지 않는다.** 중복·부분 취소·순서 역전이 있을 수 있다. 주문의 현재 상품 설정 대신 **주문 당시 스냅샷**을 기준으로 대조한다. 결제창 URL·통보 토큰·페이앱 거래번호·출처 키/해시·입력 해시·이메일·전화번호·원문 오류/SQL은 출력하지 않는다. 회원 ID·주문/지급 ID·잔액 자체는 운영 정보이므로 결과 공유 권한도 제한한다.

## 필요한 환경과 읽기 경계

기존 앱의 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_TRUSTSTORE_URL`, `DB_TRUSTSTORE_PASSWORD`를 사용한다. `DB_USERNAME=mongle_app`만 허용한다. 관리자·마이그레이션 계정은 거절한다. 추가 DB 권한·테이블·Flyway 변경은 없다. 이 조회 자체에 필요한 업무 테이블 권한은 SELECT뿐이다.

| 추가 환경변수 | 값 |
| --- | --- |
| `MONGLE_PAYMENT_INSPECT_ENABLED` | 기본 비활성. 해당 단발 실행에서만 `true` |
| `MONGLE_PAYMENT_INSPECT_EXPECTED_HOST` | 실제 JDBC URL의 호스트와 정확히 일치 |
| `MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE` | 실제 DB 이름과 정확히 일치 |

`MONGLE_PAYMENT_ENABLED`, `MONGLE_PAYAPP_ENABLED`, `MONGLE_CREDIT_ENABLED`가 꺼져 있어도 조회할 수 있다. 조회를 위해 결제·크레딧을 활성화하거나 PG 키를 설정할 필요가 없다. 기존 운영 파일을 수정하지 않고 실행별 환경값으로 제한한다.

JDBC URL은 `jdbc:mysql://호스트[:포트]/DB`로 제한한다. URL 내 인증정보·쿼리·fragment를 거절한다. 로컬 PKCS12·`VERIFY_IDENTITY`·시스템 신뢰 저장소 fallback 비활성·UTC 연결을 사용한다. `readOnlyPropagatesToServer=true`와 읽기 전용 `REPEATABLE_READ` 트랜잭션을 설정하고, 하나의 연결에서 비잠금 SELECT만 실행한 뒤 rollback/close한다. 첫 SELECT의 스냅샷 이후 다른 트랜잭션이 결제를 완료해도 신구 상태를 섞지 않는다. 변경 후 상태는 **새 조회**로 확인한다.

주문 1건·최대8개 prepared SELECT·쿼리당 결과32행 상한으로 집계만 반환한다. 전체 원장/통보 목록을 적재하지 않는다. 연결/소켓 제한 각각5초, 쿼리별 최대5초, 연결 획득 전부터 전체 조회 예산30초를 사용한다. 쿼리 사이에도 남은 시간을 확인한다. 데이터 규모·DB 장애·연결 종료 등의 영향이 있으므로 전체 프로세스의 엄격한30초 종료를 보장하지는 않는다. 아래 systemd 실행 제한60초를 함께 사용한다. 시간 초과 시 조회를 재시도할 수 있으며, 이 명령은 쓰기를 시도하지 않는다.

## EC2/SSM 실행 순서

1. PR의 CI·머지·CD 성공 후 새 릴리스에 이 명령이 포함됐는지 확인한다. 구버전 JAR에 실행하지 않는다.
2. 기존 `mongle.service`를 중지하지 않는다. `sudo readlink -f /opt/mongle/current.jar`로 확인한 **릴리스 절대 경로**를 사용한다.
3. 대상 주문의 UUID를 확인한다. 아래 가상 호스트 `mongle-db.example.invalid`를 RDS 실제 호스트로 바꾼다. DB 비밀번호·환경 파일 전체를 출력하지 않는다.
4. 단발 명령을 실행한다. 같은 이름의 실행이 남아 있으면 유닛 이름의 숫자를 바꾼다. 아래 UUID도 실제 대상 값으로 교체한다.

```bash
sudo /usr/bin/systemd-run --quiet --wait --pipe --collect \
  --service-type=exec --unit=mongle-payment-inspect-01.service \
  --property=User=mongle --property=Group=mongle \
  --property=WorkingDirectory=/opt/mongle \
  --property=EnvironmentFile=/etc/mongle/mongle.env \
  --property=NoNewPrivileges=true --property=PrivateTmp=true \
  --property=ProtectSystem=strict --property=ProtectHome=true \
  --property=RuntimeMaxSec=60 --property=TimeoutStopSec=10 \
  --setenv=MONGLE_PAYMENT_INSPECT_ENABLED=true \
  --setenv=MONGLE_PAYMENT_INSPECT_EXPECTED_HOST=mongle-db.example.invalid \
  --setenv=MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE=mongle \
  /usr/bin/java -Xms32m -Xmx128m -jar <확인한 릴리스 JAR 경로> \
  --payment-inspect=order --order-id=8b326cda-d098-4c31-9d7d-74cdd71a0a44
```

5. status와 findings/notices를 함께 확인한다. 페이앱 관리자 거래·취소 내역과 대조하고 대상·시각·판단 근거를 운영 기록에 남긴다. 개인 자료·결제 키·원문 오류는 채팅/GitHub에 붙이지 않는다.
6. 이 명령은 후속 조치를 하지 않는다. 불일치가 있으면 별도 검토로 넘기며, 직접 SQL로 잔액·주문·보류를 고치지 않는다. 안전한 회수/보류 해제 명령은 별도 구현 범위다.

## 결과와 실패 코드

- `INVALID_ARGUMENTS`, `OPERATOR_COMMAND_DISABLED`, `INVALID_CONFIGURATION`: DB 연결 전 거절.
- `DB_CONNECTION_FAILED`: TLS·인증·연결 실패. 원본 JDBC 메시지 대신 이 코드만 출력.
- `ORDER_NOT_FOUND`: UUID 형식은 맞지만 해당 주문 없음. 다른 주문으로 추측해 처리하지 않는다.
- `ORDER_DATA_INVALID`: 필수 수치/시각 등 주문 데이터 이상. 원본 값은 숨긴다.
- `INSPECTION_TIMEOUT`: 쿼리 시간/조회 예산 초과. 결과 전체를 완료 처리하지 않는다.
- `PAYMENT_INSPECTION_FAILED`, `UNSUPPORTED_LOGGING_BACKEND`: 기타 조회/출력 환경 오류. 인프라 담당자가 릴리스·접속 설정·DB 상태를 확인한다.

주요 findings는 `ORDER_GRANT_LINK_MISMATCH`/`LINKED_GRANT_MISSING`, `GRANT_OWNER_MISMATCH`/`GRANT_SOURCE_MISMATCH`/`DUPLICATE_PAYMENT_SOURCE`, `PAID_WITHOUT_MATCHING_GRANT`/`GRANT_WITHOUT_CONFIRMED_ORDER_STATE`, `PURCHASE_CREDIT_TOTAL_MISMATCH`, `ACCOUNT_GRANT_BALANCE_MISMATCH`/`ACCOUNT_LEDGER_BALANCE_MISMATCH`, `PURCHASE_GRANT_LEDGER_MISMATCH`/`PAYMENT_HOLD_LEDGER_MISMATCH`, `PURCHASE_ALLOCATION_MISMATCH`, `UNKNOWN_ORDER_STATE`/`ORDER_AMOUNT_OR_PRODUCT_INVALID`, `UNKNOWN_NOTIFICATION_STATE`/`NOTIFICATION_AMOUNT_MISMATCH`, `COMPLETION_NOTIFICATION_MISSING`/`CANCELLATION_HOLD_MISSING`이다. 하나의 원인으로 여러 코드가 함께 나올 수 있다. 위 대조는 내부 불변식 점검이며 모든 종류의 부정/데이터 손상을 탐지하는 전면 회계 감사는 아니다.

## 검증 범위

H2와 임시 MySQL 8.4에서 미결제·무상/다른 구매 혼합·구매별 예약/확정/해제·만료·취소/보류·연결/소유권/출처/잔액/원장 불일치를 검증한다. 실제 MySQL의 읽기 전용 트랜잭션에서 UPDATE가 거절되는지와, 다른 연결의 결제 커밋 중 같은 스냅샷을 유지하는지 확인한다. 모든 조회 전후 업무 테이블은 동일해야 한다.

`python3 scripts/mysql84-test.py`는 폐기되는 로컬 TLS DB만 사용한다. 패키지 JAR를 별도 JVM으로 실행해 SELECT 전용 권한에서도 조회되고, 잘못된 인자/설정/인증·없는 주문이 정제된 오류가 되며, 잘못된 서버 포트·활성 AI 플래그에도 Boot/워커가 시작되지 않는지 검증한다. 테스트에 필요한 지급/주문 SQL과 권한 변경은 이 임시 DB의 픽스처에만 적용한다. 운영 RDS/페이앱 실결제/환불은 실행하지 않는다.
