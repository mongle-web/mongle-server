# 페이앱 실결제 오픈 및 주문 복구 (#124)

## 적용 범위

카드·간편결제 제한, 판매 가능한 상품 표시, 응답 유실 주문 복구, 비밀값 없는 외부 요청 진단을 보완한다.
새 테이블·Flyway 변경·PG 자동 환불 API·프론트 UI 변경은 포함하지 않는다.
문서/테스트 완료와 운영 실결제 완료는 다르며, 실제 결제는 승인된 판매자 설정을 적용한 뒤 검증한다.

- 확정 상품: KRW 1,100원 → 10크레딧. 가입 3크레딧·무기한 정책 유지.
- 이미지: 같은 꿈/세계관 첫 성공3 → 다음 성공0(1회) → 이후 성공마다1. 실패는 예약 해제.
- 개인사업자 계정의 상품·실결제 승인 완료는 팀에서 확인했다. 실제 키·통보·청구는 배포 후 확인한다.
- 최초 구매 가능 상품 노출은 신규 결제/크레딧/Provider 활성화와 상품 enabled가 모두 필요하다.
  회원별 spendingBlocked나 외부 PG 정상 응답까지 보장하는 상태 점검은 아니며 POST에서 다시 검증한다.

## 결제수단

`PAYAPP_OPEN_PAY_TYPES=card,kakaopay,naverpay,tosspay`가 기본값이다.
`mongle.payment.payapp.open-pay-types`에 CSV를 바인딩하고 payrequest의 openpaytype으로 전달한다.
설정할 수 있는 값은 card/kakaopay/naverpay/smilepay/applepay/payco/tosspay다.
빈 목록·중복·미지원 값·phone/vbank/rbank/myaccount는 시작 시 거절한다.
여러 수단을 묶는 dvpay와 별도 위챗 결제도 이 오픈 범위에서는 허용하지 않는다.
누락 시 전체 수단을 노출하지 않고 위 안전한 기본값을 사용한다.

공식 문서에서는 판매자 설정이 우선하므로 요청 파라미터만으로 제외 수단 미노출을 단정하지 않는다.
판매자 관리자에서도 휴대폰·가상계좌 등 제외 수단을 끄고 실제 결제창과 영수증을 대조한다.
승인되지 않은 간편결제는 설정에서 제외한다. 기존 주문 통보에는 새 수단 목록을 소급 적용하지 않는다.
구매자 연락처 recvphone을 입력받는 것과 휴대폰 소액결제 phone을 제공하는 것은 별개다.

## 신규 복구 API

`GET /api/v1/payments/orders/by-request-id/{requestId}`

온보딩 완료 Bearer 인증. requestId는 주문 POST 전에 보관한 UUID다.
정규 UUID의 대소문자를 허용하고 내부 저장과 같은 소문자로 조회한다. 축약/잘못된 UUID는400 PAYMENT_400_1.
없는 주문과 타인의 주문은 동일한404 PAYMENT_404_1. 응답은 기존 PaymentOrderResponse와 같다.
Cache-Control:no-store이며 API에서 외부 결제·새 주문·지급을 실행하지 않는다.
판매 중지/상품 비활성 뒤에도 기존 주문은 조회할 수 있다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청에 성공했습니다.",
  "data": {
    "orderId": "2233e4d7-992c-48e3-b519-334fc754e925",
    "productId": "credit-pack",
    "productName": "몽글 10크레딧",
    "price": 1100,
    "currency": "KRW",
    "credits": 10,
    "state": "READY",
    "paymentUrl": "https://payapp.kr/example",
    "creditGranted": false,
    "createdAt": "2026-11-05T05:00:00"
  },
  "errors": []
}
```

예시 URL·주문은 실제 결제 대상이 아니다. 거래번호·전화번호·통보 상관값·인증값은 응답하지 않는다.

### 프론트 복구 순서

1. 결제 버튼 한 번에 requestId를 만들고 회원/화면/대상 범위로 보관한다.
2. POST 주문의 응답 orderId를 받으면 기존 orderId 조회로 진행한다. 결제창 URL 발급은 지급 성공이 아니다.
3. 타임아웃·연결 끊김·일반5xx로 응답을 잃으면 requestId 복구 API를 호출한다.
4. 아직 생성 트랜잭션이 커밋되지 않았으면404가 잠시 반환될 수 있다. 단일404로 새 UUID를 만들지 않는다.
5. 동일 POST를 보내야 하는 명시적 복구에서도 requestId/상품/전화번호를 유지한다. 기존 주문은 외부 요청 없이 재사용된다.
6. 폴링은 예를 들어3초 간격·최대2분 후 수동 재조회/문의로 전환한다. 미확정을 미결제로 단정하지 않는다.
7. PAID && creditGranted일 때 결제 성공으로 표시하고 잔액을 서버에서 재조회한다.

요청 전 거절과 미확정 구분은 HTTP 상태만 보지 않고 code를 함께 확인한다.

| 코드/상황 | 이번 호출의 의미 | 프론트 처리 |
| --- | --- | --- |
| PAYMENT_400_1 또는 입력 검증400 | 이번 호출 입력 거절 | 입력 수정. 이전 미확정 주문이 있다면 그 식별자는 보존 |
| PAYMENT_409_1 | 이번 호출의 판매 상품 거절 | 상품 재조회 |
| PAYMENT_503_1 | 이번 호출에서 신규 주문 설정 미준비 | 판매 중지 안내 |
| PAYMENT_503_2 | 이번 호출에서 신규 주문 슬롯 부족 | 잠시 후 재시도 안내 |
| CREDIT_409_5 | 이번 호출에서 결제 보류로 신규 구매 거절 | 보류 안내·문의 |
| PAYMENT_409_2 | 같은 식별자에 다른 입력 | 기존 요청을 보존하고 복구 조회 |
| PAYMENT_409_3 | 거래 귀속 충돌 | 기존 주문 조회·문의, 임의 재결제 금지 |
| 일반5xx/응답 유실/타임아웃 | 생성·외부 요청 여부 불명확 | 같은 requestId로 복구 |
| 401/403 | 인증/온보딩 필요 | 인증 복구 후 기존 주문 재조회 |

거절은 해당 호출이 새 주문을 발행하지 않았다는 뜻이며, 과거/동시 호출까지 미발행이었다는 증명이 아니다.
이전에 결과 불명확 요청이 있다면 다른 오류를 받더라도 식별자를 버리고 새 결제로 넘기지 않는다.
새 요청으로의 입력 재설정은 미확정 주문이 없는 경우에만 한다.

### 상태 및 복귀

프론트 타입·표시·폴링 종료 목록에 CREDIT_RECLAIMED를 추가한다.
이 상태는 PG 환불 상태 자체가 아니라 전액 취소된 미사용 구매 크레딧의 내부 회수 완료다.
creditGranted는 과거 지급 연결 여부라 회수 후에도 true일 수 있다. PAID와 함께 판단한다.
REQUEST_REJECTED/CANCELLED/PAYMENT_REVIEW/CREDIT_RECLAIMED는 자동 폴링을 끝내고 상황을 안내한다.

기존 /token을 공통 복귀 경로로 제안한다: https://www.mongle.art/token
프론트 합의·배포 후 PAYAPP_RETURN_URL에 넣는다. 서버가 ?orderId=UUID를 덧붙이므로 설정에 orderId를 넣지 않는다.
UI 복귀만으로 지급하지 않고 인증된 본인 주문을 조회한다.
skip_cstpage=y는 returnurl로 POST 이동하므로 현재 GET 기반 SPA에 임의로 추가하지 않는다.
결제 전용 요청 제한 시간은 서버 외부 호출 최대30초와 맞추고, 제한 시간을 늘려도 응답 유실 복구는 유지한다.
세션/회원 전환, 모바일 앱 전환, 새 탭, 원래 꿈/세계관 화면 복귀를 프론트에서 확인한다.

## 운영 설정 적용

기존 /etc/mongle/mongle.env는 백업하고 필요한 값만 편집한다. 파일 전체/키/요청 본문을 출력하지 않는다.
채팅에 노출된 키의 교체 가능 여부를 페이앱과 확인하고 새 값은 보호된 파일에 직접 입력한다.

- PAYAPP_SELLER_ID: 승인된 개인사업자 판매자 로그인 ID. 사업자등록번호/구매자 ID가 아니다.
- PAYAPP_LINK_KEY / PAYAPP_LINK_VALUE / PAYAPP_REQUEST_KEY: 같은 판매자 계정의 서로 다른 설정.
- PAYAPP_FEEDBACK_URL=https://api.mongle.art/api/v1/payments/payapp/feedback
- PAYAPP_RETURN_URL: 프론트와 확정한 HTTPS 복귀 경로.
- PAYAPP_OPEN_PAY_TYPES: 실제 승인된 카드/간편결제 목록.
- MONGLE_PAYAPP_ENABLED=true: 인증값/URL 검증 후 Provider와 통보 처리 활성화.
- MONGLE_CREDIT_ENABLED=true, MONGLE_CREDIT_EXPIRY_POLICY=NO_EXPIRY 유지.
- MONGLE_PAYMENT_ENABLED: 오픈 검증 전 false, 판매 준비 후 true.

상품은 구조화 설정으로 공급한다. 아래 YAML은 환경 파일에 그대로 붙이는 명령이 아니다.
기존 설정 주입 방식을 사용하고 다른 SPRING_APPLICATION_JSON 설정을 덮어쓰지 않는다.

```yaml
mongle:
  payment:
    products:
      - id: credit-pack
        name: 몽글 10크레딧
        price: 1100
        credits: 10
        currency: KRW
        enabled: true
```

키/상품/URL과 백엔드·프론트 배포를 맞춘 다음 신규 주문을 최종 활성화한다.
이 이슈는 신규 DDL이 없어 RDS 마이그레이션/권한 변경이 필요하지 않다.
기존 인증서·환경 파일·기능 플래그를 CD가 덮어쓰지 않으며 운영 자원을 새로 만들 필요도 없다.

### 중지·지연 통보·키 교체

신규 판매 중지는 MONGLE_PAYMENT_ENABLED=false로 한다.
이미 접수한 주문의 지급/통보를 위해 PayApp 인증 설정과 필요한 크레딧 설정은 유지한다.
통보 URL의 HTTPS FORM POST 수신, Nginx 리다이렉트/차단 여부, 서버 시간 동기화를 확인한다.

현재 새 통보의 시각 정책은 과거15분/미래2분이며 완화하지 않았다.
이미 커밋한 정확한 동일 통보는 시각 창이 지나도 인증/주문 검증 후 ACK한다.
문서의 실패 통보 재시도는10회이나 간격과 responsedate 갱신 여부는 확인이 필요하다.
처음 전달되거나 저장에 실패한 통보가 오래 지연되면 운영 주문이 미확정으로 남을 수 있다.
이 경우 [미확정 주문 조회와 복구](payment-inspection-command.md#미확정-주문-목록과-통보-복구-141) 절차로 판매자 내역을 대조하고 페이앱에 재통보를 문의한다.
신뢰할 수 있는 원본 통보와 독립 대조 근거를 확보한 경우 단발 운영 명령으로 처리할 수 있다.
통보의 시각을 임의 수정하거나 잔액을 직접 갱신하지 않는다.

키 교체 전 대기/미확정 주문을 확인한다. 기존 주문이 남아 있으면 구키 통보를 차단하지 않도록 페이앱과 전환 순서를 맞춘다.
실제 HTTPS payurl이 반환되는지 확인하고 오래된 HTTP 예시에 맞춰 서버 검증을 낮추지 않는다.

## 진단·실결제 검증

PAYAPP_REQUEST 로그는 서버 주문 UUID, 결과/이유 enum, HTTP 상태, 허용한 errno만 기록한다.
공식 REST 예제에서 확인한70010/70020/99999만 기록하고 나머지는 NONE으로 표시한다.
오류 원문/예외 체인·요청 FORM·해시·판매자 인증값·구매자 전화번호·결제 URL은 기록하지 않는다.
READY는 외부 요청 성공이며 주문 DB 저장이나 결제 완료를 의미하지 않는다. 내부 주문/원장 조회와 함께 확인한다.

운영자 통제 하에 아래를 확인한다. 실키 테스트는 실제 청구일 수 있으며 무료/자동 환불로 가정하지 않는다.
- 카드/승인된 간편결제의1,100원 결제, 제외 수단 미노출.
- PAID와10크레딧 원장1건, 중복 통보/화면 재진입에도 추가 지급 없음.
- 결제창 종료/응답 유실/다중 탭/로그인 만료/새로고침 뒤 본인 주문 복구.
- 이미지 첫 생성3/첫 재생성0/이후1, 실패 예약 해제.
- 취소·부분 취소는 원장/보류와 함께 대조. 기존 운영 조회/회수 문서를 따른다.
- 고객 문의→판매자 관리자 취소→검증 통보→내부 미사용 구매분 회수는 각각 별도 단계다.
  #119 내부 회수는 PG 환불 API가 아니며 사용/예약/부분 취소는 임의 역산하지 않는다.

## 근거 및 연동 문서

- [PayApp 공식 문서](https://docs.payapp.kr/dev_center01.html), 2026-11-05 확인.
- [기본 주문·통보 계약](payapp-payments.md)
- [운영 대조 조회](payment-inspection-command.md)
- [미사용 구매분 회수](payment-reclamation-command.md)
- [프론트 공통 연동](frontend-integration.md)

