# 전액 승인취소된 미사용 구매분 회수 (#119)

페이앱에서 이미 전액 승인취소된 구매의 **내부 크레딧**만 정리하는 단발 운영 명령이다. 공개 API·결제사 취소 API·자동 환불 기능은 추가하지 않는다. 관리자는 실제 페이앱 거래가 전액 승인취소됐는지 먼저 확인하고 비밀값 없는 사건/작업 번호를 남긴다. 서버에 저장된 통보만으로 실제 정산·환불 완료를 증명하지 않는다.

## 처리 조건

- 같은 주문·회원·PAYMENT 출처·원거래·가격·지급량·원장·보류가 일치해야 한다.
- 검증되어 저장된 결제 완료(4)와 전액 승인취소(9/64) 통보가 있어야 한다. 요청취소(8/32)만으로 회수하지 않는다. 부분취소(70/71), 미확정 주문, 뒤 시각의 완료 통보, 잘못된 거래/금액·회계 기록은 거절한다.
- 주문은 `PAYMENT_REVIEW`이고 해당 지급의 보류가 있어야 한다. available=최초 지급량, reserved=0, consumed=0이어야 한다. 부분 사용분을 역산하거나 예약을 강제로 해제하지 않는다.
- 실패 후 정상 해제된 예약 이력은 남기며, 현재 예약·사용량이 0이면 처리할 수 있다. 기한만 지난 활성 예약은 자동 해제하지 않고 거절한다.
- 해당 구매분만 회수한다. 다른 구매·가입 혜택·무상 지급분과 다른 구매의 보류는 보존한다. 다른 보류가 하나라도 있으면 계정 전체 사용 제한도 유지된다.

## 명령과 멱등성

```text
java -jar <검증한 릴리스 JAR> --payment-reclaim=preview --order-id=<UUID> --request-key=<작업 키> --operator-reference=<확인 근거 번호>
java -jar <동일한 릴리스 JAR> --payment-reclaim=apply --order-id=<동일 UUID> --request-key=<동일 키> --operator-reference=<동일 근거 번호> --expected-snapshot=<preview의 snapshot>
```

인자는 모두 `이름=값` 형식이다. preview는 정확히4개, apply는 정확히5개다. 순서는 자유지만 중복·누락·오타·다른 명령 혼합은 거절한다. UUID는 소문자 정규 형식이다. 작업 키·확인 근거는 ASCII 영문/숫자로 시작하는 영문·숫자·점·밑줄·콜론·하이픈 최대80자다. 이메일·전화번호·거래 비밀값·API 키를 넣지 않는다. `case-20261031-001` 같은 별도 운영 기록 번호를 사용하고 원본 확인 자료는 접근 통제된 운영 기록에 보관한다.

- `preview`: 읽기 전용 REPEATABLE_READ 스냅샷으로 예상 회수량, 회수 후 잔액, 남을 보류 수, 거절 사유와 `snapshot`을 반환한다. 업무 데이터를 변경하지 않는다.
- `apply`: 사용자 행 잠금을 먼저 잡고 최신 상태를 재검증한다. 대상이 달라졌으면 자동으로 새 계획을 적용하지 않고 거절한다. 새 preview 결과와 실제 거래를 다시 확인한다.
- 같은 키·주문·확인 근거·최초 snapshot으로 재실행/동시 실행해도 한 번만 회수한다. 이미 처리됐으면 `ALREADY_RECLAIMED`다. 키를 다른 주문·근거에 재사용하거나 같은 주문을 다른 키로 처리하면 `REQUEST_CONFLICT`다.
- 처리 이력·원장 등이 손상된 상태에서는 재실행으로 복구하지 않고 `RECLAMATION_RECORD_INCONSISTENT`로 거절한다.

JVM 종료·시간 초과·commit 응답 유실이면 **같은 키와 최초 snapshot**으로 재실행해 처리 여부를 확인한다. 응답을 받지 못했다고 다른 키로 다시 시작하지 않는다. snapshot은 인증 비밀키가 아닌 변경 검사용 값이며, 운영 명령 실행 권한은 EC2/SSM과 DB 비밀 파일 접근 권한으로 제한한다.

## 결과와 회계 이력

결과는 JSON 한 줄이다. 종료 코드0은 정상 조회/적용 결과가 반환됐다는 뜻이다. preview의 `REFUSED`도 종료 코드0이므로 반드시 status/reasons를 확인한다. 실패는 종료 코드1과 정제된 오류 코드다.

| status | 의미 |
| --- | --- |
| `WOULD_RECLAIM` | 사전 검증 통과. 아직 회수하지 않음 |
| `REFUSED` | 조건 미충족. 회수·보류 정리 없음 |
| `RECLAIMED` | 내부 회수 트랜잭션 커밋 완료 |
| `ALREADY_RECLAIMED` | 같은 작업으로 이미 처리. 추가 변경 없음 |

`amount`는 대상 구매의 원래 지급량, `expectedAvailableAfter`는 처리 가능 preview에서의 예상 계정 잔액 또는 처리 후 실제 잔액이다. REFUSED이면 예상 잔액은 null이다. `remainingHoldCount`와 `inspection.account.spendingBlocked`를 함께 확인한다. preview 안의 inspection은 아직 회수 전 상태다. `scope=INTERNAL_DATABASE_ONLY`는 전 과정에서 유지된다.

최초 지급 원장·지급량·사용 확정량·예약 이력은 삭제/수정하지 않는다. `payment_credit_reclamations`에 회수량·작업 키·확인 근거·당시 snapshot을 기록하고 `RECLAIM` 원장에 음수 availableDelta를 기록한다. 해당 지급의 available과 계정 available만 감소시키고 해당 지급의 보류를 정리한다. 주문은 `CREDIT_RECLAIMED`가 된다. 모두 같은 트랜잭션이며 저장/검증 실패는 전체 롤백한다.

#117 조회는 `purchase.reclaimed`, `reclamation.id/amount`를 함께 반환한다. 검증식은 `최초 지급량 = available + reserved + consumed + reclaimed`다. PAYMENT_HOLD 원장은 과거 보류 사실로 보존하고 현재 보류와 구분한다. 주문·원장 응답의 기존 필드 구조는 유지하되 enum 값이 추가되므로 [프론트 반영 사항](#프론트-반영-사항)을 확인한다.

## 배포·환경·실행

1. 최신 develop에 V6보다 앞서 예약된 버전이 없는지 확인하고 정상 CI→머지→CD의 Flyway 단계로 V6를 적용한다. V1/V3/V4/V5·초기 SQL·기존 이력은 재실행하거나 고치지 않는다. 운영 DML 계정에 DDL 권한을 추가하지 않는다.
2. CD·health와 V6 성공을 확인한다. 새 JAR에서 조회·사전 확인을 먼저 수행한다. 기존 서비스는 계속 실행하며 운영 환경 파일은 덮어쓰거나 셸에서 source하지 않는다.
3. 기존 `DB_URL`, `DB_USERNAME=mongle_app`, `DB_PASSWORD`, 로컬 PKCS12의 `DB_TRUSTSTORE_URL/PASSWORD`를 사용한다. TLS VERIFY_IDENTITY·대상 호스트/DB 검증을 유지한다. 마이그레이션 계정으로 명령을 실행하지 않는다.
4. 아래3개 값은 **해당 실행에만** 지정한다. 영구 환경 파일에 추가하거나 서비스를 재시작할 필요는 없다. `MONGLE_PAYMENT_RECLAIM_ENABLED` 기본값은 비활성이다. MONGLE_PAYMENT/PAYAPP/CREDIT 기능이 꺼져 있어도 기존 취소 건을 정리할 수 있다.

```ini
MONGLE_PAYMENT_RECLAIM_ENABLED=true
MONGLE_PAYMENT_RECLAIM_EXPECTED_HOST=<실제 RDS 호스트>
MONGLE_PAYMENT_RECLAIM_EXPECTED_DATABASE=mongle
```

실행 예시다. 아래 가상 호스트·주문 UUID·작업 번호를 실제 확인한 값으로 바꾼다. JAR는 `sudo readlink -f /opt/mongle/current.jar`로 확인한 새 릴리스 절대 경로를 넣는다. 유닛 이름이 사용 중이면 끝의01을 바꾼다.

```bash
sudo /usr/bin/systemd-run --quiet --wait --pipe --collect \
  --service-type=exec --unit=mongle-payment-reclaim-preview-01.service \
  --property=User=mongle --property=Group=mongle \
  --property=WorkingDirectory=/opt/mongle \
  --property=EnvironmentFile=/etc/mongle/mongle.env \
  --property=NoNewPrivileges=true --property=PrivateTmp=true \
  --property=ProtectSystem=strict --property=ProtectHome=true \
  --property=RuntimeMaxSec=90 --property=TimeoutStopSec=10 \
  --setenv=MONGLE_PAYMENT_RECLAIM_ENABLED=true \
  --setenv=MONGLE_PAYMENT_RECLAIM_EXPECTED_HOST=mongle-db.example.invalid \
  --setenv=MONGLE_PAYMENT_RECLAIM_EXPECTED_DATABASE=mongle \
  /usr/bin/java -Xms32m -Xmx128m -jar <확인한 릴리스 JAR 경로> \
  --payment-reclaim=preview \
  --order-id=8b326cda-d098-4c31-9d7d-74cdd71a0a44 \
  --request-key=reclaim-20261031-001 \
  --operator-reference=case-20261031-001
```

`WOULD_RECLAIM`과 대상·예상 수량·남은 보류를 확인한 뒤 같은 명령에서 유닛 이름을 apply용으로 바꾸고 `--payment-reclaim=apply`, `--expected-snapshot=<확인한 snapshot>`을 지정한다. 적용 후 동일 새 JAR의 #117 조회와 본인 크레딧 잔액/내역을 확인하고 운영 기록에 결과를 남긴다. REFUSED·불일치·응답 유실을 수동 SQL로 덮어쓰지 않는다.

연결·소켓 제한5초, 쿼리별 최대5초, 전체 작업 예산30초를 사용한다. 실제 MySQL apply는 innodb_lock_wait_timeout=5초다. 한 주문의 집계만 조회하고 결과 행은32행 상한이다. 웹·OAuth·AI·S3·PG 클라이언트/워커를 시작하지 않는다. 외부 장애·프로세스 종료를 고려해 위 systemd 제한과 동일 키 재실행을 함께 사용한다.

**롤백 경계:** V6 자체는 테이블 추가이므로 기존 잔액을 변경하지 않는다. 하지만 운영 명령을 처음 적용한 뒤에는 `CREDIT_RECLAIMED`/`RECLAIM` 데이터가 생긴다. 이 값을 모르는 #119 이전 JAR로는 되돌리지 않는다. 관련 통보와 조회를 지원하는 버전으로 복구/전진 배포한다. 회수 이력을 지우거나 지급량·사용량을 바꿔 구버전에 맞추지 않는다. 신규 JAR의 CI·CD·종단 확인이 끝나기 전에는 실제 회수 명령을 적용하지 않는다.

## 오류·거절 처리

- INVALID_ARGUMENTS / OPERATOR_COMMAND_DISABLED / INVALID_CONFIGURATION: DB 접근 전 입력·기본 비활성·대상 설정 검증 실패.
- DB_CONNECTION_FAILED / RECLAMATION_TIMEOUT / PAYMENT_RECLAMATION_FAILED: 접속·제한 시간·기타 실행 실패. 결과 유실이면 동일 키로 확인한다.
- ORDER_NOT_FOUND / ORDER_DATA_INVALID: 없는 주문·내부 데이터 이상.
- SNAPSHOT_CHANGED: 사전 확인 이후 상태 변경. 실제 거래부터 다시 확인한 뒤 새 preview 사용.
- RECLAMATION_NOT_ALLOWED: apply 시 조건 미충족. preview reasons와 내부 대조 결과를 확인한다.
- REQUEST_CONFLICT / RECLAMATION_WRITE_CONFLICT: 키·기존 처리·동시 쓰기 충돌. 새 키로 우회하지 않는다.
- RECLAMATION_RECORD_INCONSISTENT / POSTCONDITION_FAILED: 기존 이력 또는 적용 후 검증 불일치. 새 변경은 롤백하며 운영 검토로 넘긴다.

부분 사용·부분취소·실제 환불 신청/PG 취소 API·임의 보류 해제는 후속 범위다. 모든 보류를 한꺼번에 지우는 명령은 제공하지 않는다.

## 프론트 반영 사항

- `GET /api/v1/payments/orders` 및 개별 주문의 state에 `CREDIT_RECLAIMED` 추가. 결제 성공으로 처리하지 않는다. `creditGranted`는 과거 지급 연결 유무로 기존 계약을 유지하며 현재 사용 가능 잔액은 credits/balance로 확인한다.
- 크레딧 내역의 kind에 `RECLAIM` 추가. availableDelta가 음수인 회수 내역으로 표시하고 일반 이미지 사용(SETTLE)과 구분한다.
- 회수 실행용 프론트 버튼·API는 추가하지 않는다. 고객의 원결제 환불 완료 여부를 이 상태만으로 단정하지 않는다.

## 검증

H2·임시 TLS MySQL에서 정상 회수/다른 지급 보존, 다른 보류 유지, 활성·만료 예약/사용/부분취소/요청취소/상충 통보 거절, snapshot 변경, 멱등성·동시 실행·전체 롤백을 검증한다. 실제 verifier/서비스를 통한 늦은 통보 회귀 테스트와 V5→V6 데이터·기존 체크섬 보존도 포함한다. 배포 JAR는 SELECT 권한만 있는 preview와 DML apply·재실행·독립 JVM 동시 실행을 검증한다. 모든 지급·취소·권한 변경은 폐기되는 로컬 테스트 DB 픽스처에만 적용하며 운영 결제/환불을 실행하지 않는다.
