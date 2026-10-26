# 페이앱 단건 크레딧 충전 (#62)

`feat/60-credit-ledger`의 #61 위에서 구현했다. 새 API는5개이며 기존 잔액/원장 API는 재사용한다.
실제 페이앱 계정·키·상품 허용·청구·정산을 확인한 상태가 아니다. 기본은 신규 결제/Provider/크레딧 지급 모두 비활성이다.
유상/무료 크레딧 모두 무기한으로 사용하는 정책을 반영해 expiry-policy 기본은 NO_EXPIRY로 변경했다.
구매 환불 신청·자동 환불·실물 상품·가입 자동 지급·이미지 과금 호출부는 포함하지 않는다.

## 공개 계약

모든 업무 API는 온보딩 완료 Bearer 인증과 기존 ApiResponse 봉투를 사용하며 Cache-Control:no-store를 반환한다.
페이앱 통보는 별도 FORM POST만 공개하고 JWT 대신 통보 인증을 검증한다. 다른 메서드/결제 경로의 인증을 해제하지 않는다.

| API | 책임 |
| --- | --- |
| GET /api/v1/payments/products | 설정된 활성 상품 목록. 미설정이면 빈 목록 |
| POST /api/v1/payments/orders | requestId·productId·buyerPhone으로 주문 생성/재사용, 결제창 URL 반환 |
| GET /api/v1/payments/orders/{orderId} | 본인 주문 상태·지급 여부 조회 |
| GET /api/v1/payments/orders?beforeId=&size=20 | 본인 주문 내역, ID 내림차순·size1~50 |
| POST /api/v1/payments/payapp/feedback | 페이앱 서버 FORM 통보, 커밋 완료 후 plain text SUCCESS |

상품 응답은 productId/name/price/currency/credits다. 이번 어댑터는 **국내 휴대폰·원화(KRW) 정수 결제**를 지원한다.
활성 상품의 최종 결제금액은 공식 최소금액1000원 이상 int 범위, 지급 크레딧은 양수 long이다. USD 등은 조용히 변환하지 않고 설정을 거절한다.
가격·지급량은 운영 기본값을 넣지 않았다. 다른 통화·세금 분리·국제전화 지원이 필요하면 해당 계약을 별도 검증해 확장한다.
금액·지급량·회원 ID·복귀 URL·통보 URL을 클라이언트가 결정하지 않는다. 구매자 휴대폰은 국내01x 형식의10~11자리 숫자이며 사용자 프로필 필수값으로 확대하지 않는다.

주문 요청 예시는 형태만 나타낸다.

```json
{"requestId":"2233e4d7-992c-48e3-b519-334fc754e925","productId":"credit-pack","buyerPhone":"01012345678"}
```

requestId는 결제 버튼 한 번의 UUID이며 네트워크 재전송 시 그대로 사용한다. 동일 ID의 상품/전화번호 변경은409다.
새 주문은 서버 상품명·최종 금액·통화·지급량을 보존하므로 이후 상품 설정 변경이 기존 구매 조건을 바꾸지 않는다.
이미 생성된 주문은 상품/신규 결제 비활성화 후에도 같은 요청으로 조회할 수 있다. 회원마다 같은 UUID는 서로 다른 주문이다.
전화번호는 Provider 요청 메모리에서만 사용한다. 주문에는 중복 입력 비교용 SHA-256 fingerprint만 보존하며 원문 전화번호를 저장/반환하지 않는다.
fingerprint도 개인정보 관련 값으로 취급해 외부 API/로그에 노출하지 않는다.

주문 응답: orderId/productId/productName/price/currency/credits/state/paymentUrl/creditGranted/createdAt.
orderId는 UUID, createdAt은 기존 계약의 UTC LocalDateTime이다. 거래번호·통보 비밀 상관값·전화번호·인증 값은 노출하지 않는다.
목록은 items/hasNext/nextBeforeId이며 hasNext일 때 nextBeforeId를 다음 요청 beforeId로 사용한다. 전체 count/N+1 조회는 없다.

| 상태 | 화면 처리 |
| --- | --- |
| REQUESTING | 결제창 발급 진행. 같은 요청의 중복 응답은202, 주문 조회로 확인 |
| READY | paymentUrl로 결제창 이동 가능. 아직 결제 완료/크레딧 지급 아님 |
| REQUEST_REJECTED | 페이앱이 결제 요청을 거절. 자동 재호출 없음 |
| UNKNOWN | 외부 요청 결과 미확정. 자동으로 새 requestId를 발급하거나 결제 요청을 재전송하지 않음 |
| PENDING | 검증된 요청/입금대기 통보 수신. 지급 전 |
| PAID | 검증된 결제 완료와 크레딧 지급 커밋 완료 |
| CANCELLED | 지급 전 취소 확인. 추가 지급하지 않음 |
| PAYMENT_REVIEW | 취소/순서 충돌 등의 결제 정합성 확인 필요. 자동 재지급하지 않음 |

creditGranted는 과거 지급이 존재하는지 나타내므로 state와 함께 확인한다.
결제 요청이 이미 끝났으면 POST는200이며 성공 구매를 뜻하지 않는다. 정상 요청도 통보가 먼저 도착하면 바로 PAID가 반환될 수 있다.
응답 유실/서버 재시작으로45초를 넘긴 REQUESTING은 조회 응답에서 UNKNOWN으로 표시한다. 요청 대기열/자동 재전송은 없다.

## 통보 검증과 상태 전이

- FORM 본문은 Content-Length 유무와 무관하게 읽는 동안32KiB로 제한한다. 중복 필드와 잘못된 인코딩은 거절하고 새로운 비관련 필드는 허용한다.
- userid/linkkey/linkval을 상수 시간 비교하고 responsedate(한국 시간14자리)와 공식 SHA-256(userid+responsedate+requestkey)를 검증한다.
- 신규 통보는 현재 시각15분 전~2분 후 범위를 허용한다. 이는 서버의 수신 정책이며 페이앱 재시도 기간 보장이 아니다. 서버 시간을 동기화하고 실제 통보/재시도 시각 계약을 운영 연결 때 확인한다.
- var1은 주문 UUID, var2는 주문 생성 당시 무작위 비밀 상관값이다. 주문 금액과 거래번호 소유관계까지 확인한다. 이메일/전화번호/브라우저 복귀를 계정 또는 지급 증거로 사용하지 않는다.
- 거래번호는 전역으로 한 주문에만 귀속된다. 부분 취소는 orig_mul_no/orig_price를 원주문과 검증하고 취소 거래번호도 별도 귀속한다.
- 결제 요청 응답보다 통보가 먼저 와도 가능하다. 검증된 통보가 최초 거래를 결합하며, 늦은 요청 응답이 PAID/취소를 READY로 되돌리지 못한다.
- 통보 처리는 READ_COMMITTED와 사용자 행 잠금 후 최신 주문 재조회로 직렬화한다. 완료 상태/주문별 grant/불변 원장/통보 처리가 함께 커밋 또는 롤백된다.
- 동일 통보 fingerprint를 영속한다. 정확한 중복 통보는 인증/주문/금액을 다시 확인하되 시각 창이 지나도 재지급 없이 ACK한다.
- 1/10은 요청/대기,4는 완료,8/32는 요청취소,9/64는 승인취소,70/71은 부분취소로 현재 공식 표에 맞춘다. 미지원 상태는 성공 처리하지 않는다.
- 저장 성공 후 HTTP200 text/plain SUCCESS, 검증 실패는4xx FAIL, 저장/설정 실패는5xx FAIL이다. 재통보가 와도 중복 지급하지 않는다.
- 비밀값·휴대폰·카드/계좌·원문 FORM·Provider 오류 본문을 저장/로그/예외 체인으로 남기지 않는다. 안전한 거래/금액/상태 감사 자료만 DB에 보존한다.

## 외부 취소 후 지급분 보호

구매 환불 신청 API를 만들지 않는 것과 결제사가 보낸 취소를 무시하는 것은 다르다.
지급 전 취소는 CANCELLED이고 이후 완료 통보가 와도 임의로 지급하지 않고 PAYMENT_REVIEW로 둔다.
이미 지급한 주문이 승인/부분 취소되면 PAYMENT_REVIEW와 credit_payment_holds를 함께 저장한다.
부분 사용의 환불 단가나 회수량을 임의로 계산하지 않으며 금액/크레딧 잔액도 삭제하지 않는다.

잔액 API에 spendingBlocked를 추가했다. true이면 **계정의 신규 크레딧 예약·새 사용 확정·새 구매 주문**이 CREDIT_409_5로 거절된다.
이미 확정된 동일 결과의 재확인은 가능하며 실패 예약 해제/만료 복구·잔액/주문 조회는 계속 가능하다.
PAYMENT_HOLD 원장 항목은 수량 변동0의 검토 표시다. 같은 주문의 취소 중복은 원장/보류를 중복 기록하지 않는다.
보류 자동 해제·사용분 역산 회수·원결제 수단 환불은 이번 기능에 포함되지 않는다. 실제 결제 내역과 지급/예약/사용 원장을 확인한 운영 조치가 필요하다.
프론트는 spendingBlocked와 PAYMENT_REVIEW를 확인해 구매/사용 버튼 대신 확인 필요 안내를 표시한다.

## 설정

| 설정 | 기본값 |
| --- | --- |
| MONGLE_PAYMENT_ENABLED | false: 신규 주문 차단 |
| MONGLE_PAYAPP_ENABLED | false: 실제 Provider/통보 비활성 |
| MONGLE_CREDIT_ENABLED | false: 신규 지급/예약 비활성 |
| MONGLE_CREDIT_EXPIRY_POLICY | NO_EXPIRY |
| PAYAPP_SELLER_ID / PAYAPP_LINK_KEY / PAYAPP_LINK_VALUE / PAYAPP_REQUEST_KEY | 빈 값, 서버 비밀 설정에서 주입 |
| PAYAPP_FEEDBACK_URL | 빈 값. HTTPS /api/v1/payments/payapp/feedback 정확한 경로 |
| PAYAPP_RETURN_URL | 빈 값. HTTPS 프론트 복귀 페이지. orderId는 서버가 query에 추가 |
| PAYAPP_CONNECT_TIMEOUT | 3s |
| PAYAPP_TOTAL_TIMEOUT | 30s: connect 이상·최대30s |

products는 운영 YAML/환경의 구조화 설정으로 공급한다. 빈 목록이 기본이다. 다음 비활성 틀의 금액/수량을 회의 결과로 채운 뒤 검증한다.

```yaml
mongle:
  payment:
    products:
      - id: credit-pack
        name: null
        price: null
        credits: null
        currency: KRW
        enabled: false
```

상품은 중복 없는 최대50개이며 상품 ID는 소문자 영문 시작의 영문/숫자/하이픈50자 이하다. 활성 상품의 미설정/잘못된 값은 시작 시 거절한다.
상품 이름은80코드포인트 이하이며 URI/제어문자를 넣지 않는다. 세금 항목을 별도 지정하지 않고 페이앱 판매자 과세 설정을 사용한다. 실제 영수증/과세 설정은 판매 전 확인한다.
실제 요청 주소는 https://api.payapp.kr/oapi/v2로 고정한다. HTTPS FORM 요청, URL-encoded 응답, 리다이렉트 금지, 결제창 URL은 HTTPS payapp.kr 또는 그 하위 호스트만 허용한다.
전체 본문 수신·청크 응답을 포함해30초 내 취소하며 응답32KiB를 초과하면 통신을 취소하고 UNKNOWN으로 둔다. 자동 유료 요청 재시도는 없다.
새 주문은 인스턴스당8개까지 처리하고 완료 DB 작업만2스레드/큐8개에서 수행한다. 외부 HTTP 대기는 풀/DB 잠금을 점유하지 않는다.
신규 결제 중지는 MONGLE_PAYMENT_ENABLED=false로 한다. 이미 접수한 주문의 통보 처리를 위해 기존 판매자 인증 설정과 필요한 지급 설정은 유지해야 한다.
복귀 URL은 화면 이동용이다. 프론트가 받은 성공 이벤트로 grant를 호출하는 경로는 없다. 키를 VITE_ 변수/저장소/채팅에 넣지 않는다.

## DB와 검증 경계

신규 DB는 db/schema-initial.sql, #60까지 적용된 기존 DB는 앱 중지·백업 후 db/migrations/20261026-payapp-payments.sql을 수동 적용한다.
payment_orders/payment_transaction_bindings/payment_notifications/credit_payment_holds 4테이블을 추가한다. 원장 kind는 VARCHAR이므로 PAYMENT_HOLD 추가 시 별도 enum DDL은 없다.
H2 MySQL 모드의 초기 스키마 Hibernate validate와 마이그레이션2회 적용을 검증했다. 실제 MySQL/RDS 실행·격리/잠금 동작은 별도 운영 검증이 필요하다.
모의 HTTP로 인코딩·응답 제한/타임아웃·비리다이렉트, 실제 앱 주문→모의 페이앱→먼저 도착하는 HTTP통보→크레딧 지급까지 검증했다.
중복/동시 통보·위변조·순서 역전·지급/취소 롤백·본인 권한/페이지·예약 사용 보류을 검증한다.
실제 키/상품 가격을 넣어 청구하지 않았다. 페이앱 실제 계정·키·상품 허용과 공개 HTTPS 통보 URL·최종 상품값이 있어야 실결제 종단 검증을 완료할 수 있다.

## 공식 근거

- https://docs.payapp.kr/dev_center01.html (2026-10-26 확인)

## 환불 기능과 정책의 구분

서비스 내 구매 환불 신청·자동 회수/보류 해제 API는 아직 없다. 이는 미사용 유료 크레딧을 일괄 환불 불가로 정한 정책이 아니다. 법정 청약철회·환불을 접수하고 실제 거래와 지급 출처·예약·사용량을 대조해 처리하는 별도 운영 경로가 필요하다. 페이앱 관리자 취소만으로 몽글 크레딧 회수가 끝나지는 않는다. 외부 취소 수신 시 현재 구현은 사용 보류를 설정한다.

#64 이미지 생성 실패의 예약 해제는 구매 대금 환불과 다르다. 사용하지 않은 예약을 원래 잔액으로 돌려주며 이미 성공 확정한 생성 비용을 임의로 반환하지 않는다. 무기한 크레딧 정책을 환불 권리 포기로 해석하지 않는다.
