# 크레딧 원장과 예약 계약

## 범위

크레딧 계정·지급 출처·불변 원장·생성 시도별 예약을 제공한다. 페이앱의 상품/주문/결제 통보 검증은 #62에서 연결했다. 개별 이미지 생성 과금 호출부는 #64에서 연결했다. 가입 시 자동 지급과 세계관 전체 이미지 API는 후속 기능이다. [결제 계약](payapp-payments.md)을 참고한다. 클라이언트용 임의 지급·잔액 수정 API는 없다.

꿈 연결·세계관 이야기 구성·폴더 편집과 기존 비이미지 기능에는 과금을 추가하지 않는다. 개별 꿈/세계관 전체 이미지의 생성·명시적 재생성을 구분한다. 기존 성공 결과 조회·재사용은 무료이며 실제 새 시도에서만 예약한다.

## 공개 API

| API | 응답 |
| --- | --- |
| GET /api/v1/credits/balance | available, reserved, total |
| GET /api/v1/credits/transactions?beforeId=&size=20 | items, hasNext, nextBeforeId |

Bearer 인증과 온보딩이 필요하다. 본인 내역만 반환하고 Cache-Control: no-store를 설정한다. 미지급 계정 조회는 모두0을 반환하며 계정 생성·지급을 하지 않는다. 기능 비활성 상태에서도 조회할 수 있다.

total=available+reserved이며 사용 확정된 양은 제외한다. 원장은 ID 내림차순, size1~50이며 hasNext일 때 nextBeforeId를 다음 beforeId로 전달한다. 각 내역에는 kind/source/usage, availableDelta/reservedDelta, availableAfter/reservedAfter, createdAt이 포함된다. source/usage는 해당하지 않는 종류에서 null이다. createdAt은 기존 API와 같은 서버 UTC 기준 LocalDateTime이다. 결제사 키·내부 요청 키·지급 출처 키는 노출하지 않는다.

## 내부 계약과 트랜잭션

- CreditService.grant(userId, source, sourceKey, amount): 검증한 결제 상태 저장과 같은 트랜잭션에서 호출한다. PAYMENT/SIGNUP/PROMOTION을 구분하고 출처별 지급 원본 수량을 보존한다. 같은 출처·키·소유자·수량은 기존 지급을 반환하고 다른 내용은 충돌한다. 출처의 전역 UNIQUE가 같은 주문의 다른 계정 지급도 방어한다.
- CreditReservationService.reserve(userId, requestKey, usage, targetKey, expiresAt): 생성 시도 저장과 같은 트랜잭션에 참여한다. targetKey에는 이미지 ID만이 아니라 해당 시도/attempt 식별자를 연결한다. 부족한 잔액으로 외부 AI를 실행하면 안 된다.
- settle(userId, reservationId, resultKey): 성공 결과/버전 저장과 같은 트랜잭션에서 실행한다. resultKey는 영속 결과 ID이며 URL·원문을 넣지 않는다. 결과 저장이 롤백되면 확정도 롤백된다.
- release(userId, reservationId, reason): FAILURE/CANCELLED/EXPIRED를 구분한다. 생성 실패 예약 해제는 구매 결제 환불이 아니다. 이미 해제됐으면 최초 사유를 보존한다.
- get(userId, reservationId): 내부 상태 확인용이다. 다른 소유자의 예약은404다.
- CreditReservationRecovery.recoverExpired(batchSize): 트랜잭션 밖에서 호출하는1~100개 제한 스캔이다. 각 항목은 별도 트랜잭션에서 사용자 잠금 후 상태·기한을 재확인한다. #64의 이미지 복구 워커가 이미지 시도 복구 후 이 스캔도 자동 호출한다.

모든 변경은 사용자 행을 먼저 잠근다. 후속 작업도 같은 잠금 순서를 따른다. @Version과 고유 제약은 상위 트랜잭션에 오래된 객체가 남아 있는 경우의 갱신 손실도 막는다. 충돌/제약 위반 후 같은 트랜잭션을 재사용하지 말고 전체 작업을 롤백한 뒤 필요하면 새 트랜잭션에서 상태를 재조회한다. 외부 결제·AI·파일 저장 중 DB 트랜잭션과 잠금을 유지하지 않는다.

내부 키는1~128자의 ASCII 영문·숫자·점·밑줄·콜론·슬래시·하이픈이며 첫 글자는 영문·숫자다. 해시 UNIQUE로 MySQL 기본 collation과 무관하게 대소문자를 구별한다.

## 예약과 지급 출처

| 동작 | 사용 가능 | 예약 중 | 원장 kind |
| --- | --- | --- | --- |
| 지급 N | +N | 0 | GRANT |
| 예약 N | -N | +N | RESERVE |
| 성공 확정 N | 0 | -N | SETTLE |
| 실패/취소/만료 해제 N | +N | -N | RELEASE |

지급 출처는 오래된 ID부터 배분하며 배분 관계를 영구 기록한다. 각 출처의 available/reserved/consumed를 구분해 구매분과 무상 지급분을 추적한다. 계정 잔액과 지급 출처·예약·원장이 같은 트랜잭션으로 변경된다. 금액이 아닌 정수 크레딧이며 long 범위 초과와 음수 잔액은 거절한다.

예약 상태는 RESERVED에서 SETTLED 또는 RELEASED로 한 번만 이동한다. 확정 후 해제·해제 후 확정은 충돌한다. 동일 resultKey의 확정 재전송은 기존 성공을 반환한다. 중복 예약은 최초 사용량·기한·최종 상태를 유지하며 연장/새 차감을 하지 않는다. 설정이 바뀌어도 기존 예약 수량은 바뀌지 않는다. 다른 요청 키로 같은 usage/target 시도를 다시 예약할 수도 없다.

expiresAt은 해당 생성 시도의 lease 기한이다. DB DATETIME(6) 정밀도로 저장하며 기한이 지난 예약은 성공 확정을 거절한다. 이 내부 확정 API가 예외를 던진 트랜잭션은 롤백한다. 이미지 호출부는 확정 전 만료를 확인해 실패 기록과 해제를 함께 저장하며, 누락된 만료 예약은 복구 스캔이 한 번만 해제한다. 프로세스 메모리나 콜백 유무에 의존하지 않는다. 중복 요청이 반환한 예약이 이미 만료/종료 상태면 호출부는 새 외부 생성을 시작하지 않아야 한다.

## 설정과 미정 정책

| 환경변수 | 기본값/동작 |
| --- | --- |
| MONGLE_CREDIT_ENABLED | false: 신규 지급/예약 비활성 |
| MONGLE_CREDIT_EXPIRY_POLICY | NO_EXPIRY |
| MONGLE_CREDIT_SIGNUP_AMOUNT | 미설정. 자동 가입 지급은 아직 연결하지 않음 |
| MONGLE_CREDIT_DREAM_IMAGE_GENERATION | 미설정: 해당 새 예약 거절 |
| MONGLE_CREDIT_DREAM_IMAGE_REGENERATION | 미설정 |
| MONGLE_CREDIT_WORLD_IMAGE_GENERATION | 미설정 |
| MONGLE_CREDIT_WORLD_IMAGE_REGENERATION | 미설정 |

유상/무료 모두 무기한 정책으로 확정해 기본값은 NO_EXPIRY다. 이전 UNDECIDED를 명시한 운영 설정은 활성화 전 수정해야 한다. 상품 유효기간과 예약 lease는 다른 값이며, 생성 시도의 예약 기한/복구는 유지한다.

이미지 사용량은 설정된 경우 양수여야 하고, 가입 지급량은0 이상이다. 미설정 기능은503으로 실패한다. 예제/테스트 수치를 운영 기본값으로 사용하지 않는다. 새 사용을 비활성화해도 기존 예약의 확정·실패 해제·만료 복구는 계속 가능하다. 결제 상품 가격은 페이앱 상품 설정으로 관리한다. #62의 외부 취소 통보는 PAYMENT_HOLD 원장/계정 사용 보류로 보호한다. 구매 환불 신청·자동 회수/보류 해제는 구현하지 않았다.

## 오류와 DB 적용

CREDIT_400_1 내부 입력, CREDIT_400_2 페이지, CREDIT_404_1 없음/다른 소유자,
CREDIT_409_1 잔액 부족, CREDIT_409_2 요청 충돌, CREDIT_409_3 예약 상태 충돌,
CREDIT_409_4 수량 범위 초과, CREDIT_503_1 미준비/미설정이다. 공개 API의 형식 오류는 기존 COMMON 오류 계약도 따른다.

새 DB는 schema-initial.sql, 기존 DB는 db/migrations/20261026-credit-ledger.sql을 배포 절차에서 수동 적용한다. 기존 꿈/AI 테이블을 변경하지 않는다. 운영 DB에는 자동 실행하지 않는다. H2 MySQL 모드에서 초기 스키마·재실행을 검증했으며 실제 MySQL/RDS 검증은 별도다.

## 외부 결제 확인 중 사용 보류 (#62)

잔액 응답에 spendingBlocked를 추가했다. 결제 취소 확인 중이면 true이며 새 예약/사용 확정/구매가 CREDIT_409_5로 거절된다. 기존 확정의 멱등 재확인·예약 해제·만료 복구·조회는 유지한다. PAYMENT_HOLD는 잔액 변화0의 검토 원장 항목이며 자동으로 크레딧을 역산해 회수하지 않는다. [결제 처리와 운영 경계](payapp-payments.md)를 참고한다.

## 개별 이미지 과금 연결 (#64)

새 이미지 시도의 UUID와 내부 예약 ID를 연결한다. 생성 시도 저장+예약, 성공 결과 저장+확정, 실패 기록+예약 해제는 각각 같은 트랜잭션이다. 외부 이미지/스토리지 IO는 커밋 후 실행한다. DREAM_IMAGE_GENERATION과 DREAM_IMAGE_REGENERATION 비용은 기존 설정을 사용하며 미설정/비활성은 CREDIT_503_1이다. 기존 성공 재사용·상태 조회·진행 중복에는 비용 검사를 실행하지 않는다.

생성 중 PAYMENT_REVIEW가 생기거나 예약이 해제/만료되면 새 파일을 공개하지 않고 예약을 해제한다. 이전 성공 결과는 유지한다. 새 꿈 연결·분석·서사·폴더 과금은 추가하지 않는다. 세계관 전체 이미지 API는 별도 구현한다.

### 만료·삭제 복구

`mongle.image.recovery.enabled`는 기본 true이며 30초 간격, 배치20개로 시작한다. `MONGLE_IMAGE_RECOVERY_ENABLED`, `MONGLE_IMAGE_RECOVERY_POLL_INTERVAL`(1초~5분), `MONGLE_IMAGE_RECOVERY_BATCH_SIZE`(1~100)로 조정한다. 과금·Provider·S3 비활성 여부와 독립적으로 기존 예약을 복구한다. AI를 재호출하지 않는다.

이미지 임대 만료 시 사용자 잠금 후 시도/기한을 다시 확인하고 예약 해제·실패 저장·시도별 PNG/JPEG 정리 요청을 함께 커밋한다. 다음으로 원장의 연결 없는 만료 예약을 제한된 배치로 해제한다. 실패는 다음 주기에 재시도하며 중복 워커는 한 번만 해제한다. 꿈 영구 삭제도 같은 트랜잭션에서 남은 예약을 해제하고 정리 요청을 보존한다. 이미 확정한 크레딧은 꿈 삭제로 반환하지 않는다.

정리 요청의 실제 파일 삭제는 별도 이미지 정리 워커/S3 활성화가 필요하다. 종료 후 늦게 업로드하는 경우에도 결과 채택을 차단하며, 외부 저장소와 DB 사이의 모든 중단 지점에 대해 완전한 객체 회수를 보장하는 것은 아니므로 운영에서 미참조 객체 대조를 유지한다.

새 DB는 초기 스키마, 기존 DB는 앱/워커 중지·백업 후 `db/migrations/20261026-image-credit-charging.sql`을 수동 적용한다. nullable 예약 ID·만료 인덱스만 추가하며 기존 성공 결과는 무료 재사용한다. 예약 없는 구버전 진행 시도의 새 성공 반영은 허용하지 않는다. 실제 MySQL 프로시저 실행은 운영 DB 검증 대상이다.
