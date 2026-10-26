# 몽글 프론트엔드 연동 전달본

2026-10-27 기준. 이번 #76은 배포 자동화 작업이며 기능 API를 추가·변경하지 않는다. 기능 API 기준 커밋은 develop `ea35da40ccf5000a147f0b0288fa37391b5aa580`이다. 실제 JAR를 로컬에서 실행해 OpenAPI3.1 / 45개 경로 / 55개 API를 다시 추출했다. **현재 구현 계약과 최종 MVP 예정 기능, 운영 활성 상태를 구분한다.**

## 주소와 명세 전달

| 용도 | 주소/상태 |
|---|---|
| 프론트 대표 주소 | https://www.mongle.art |
| API 기본 주소 | https://api.mongle.art |
| 공통 API 접두사 | /api/v1 |
| 운영 health | https://api.mongle.art/actuator/health — 이번 확인 HTTP200/UP |
| 운영 Swagger | https://api.mongle.art/swagger-ui.html — 이번 확인404, 아직 사용 불가 |
| 운영 OpenAPI JSON | https://api.mongle.art/v3/api-docs — 이번 확인404, 아직 사용 불가 |
| 로컬 Swagger | http://localhost:8080/swagger-ui.html |
| 로컬 OpenAPI JSON | http://localhost:8080/v3/api-docs |

이번 공개 요청 점검에서는 `https://www.mongle.art`의 Bearer GET preflight가 HTTP200 및 정확한 Allow-Origin으로 응답했다. 모든 메서드·실제 인증·브라우저 기능 연동 완료까지 뜻하지 않는다. 운영 JAR는 마지막 확인 기준 `mongle-f506c173.jar`라서 최신 develop의 #70 로그인 개선과 구분해야 한다.

운영 문서는 공통 설정에서 비활성이다. 인프라 담당이 공개 범위를 확인한 뒤 `SPRINGDOC_API_DOCS_ENABLED=true`, `SPRINGDOC_SWAGGER_UI_ENABLED=true`를 기존 서버 환경에 적용하고 배포/재시작해야 운영 URL을 사용할 수 있다. 이 전달문이나 #76이 문서를 임의로 활성화하지 않는다.

우선 별도 첨부하는 **프론트 공유용 OpenAPI JSON**을 Postman 등의 Import 기능으로 사용한다. JSON의 운영 주소는 호출 대상 설정이며, 모든 기능이 운영에서 활성화됐다는 의미가 아니다. 원본 추출 JSON의 `127.0.0.1:18176`은 임시 추출 서버이며 공유 서버가 아니다.

## 프론트 API 기본 설정

```dotenv
VITE_API_BASE_URL=https://api.mongle.art
```

API 클라이언트가 위 환경변수를 실제로 읽도록 연결한다. 경로에는 `/api/v1/...`를 붙인다. baseURL에도 `/api/v1`를 넣고 요청 경로에도 다시 넣지 않는다.

- 업무 API: `Authorization: Bearer <accessToken>`.
- JSON 요청: `Content-Type: application/json`.
- Refresh/CSRF/로그아웃 요청: 브라우저 쿠키 포함(`credentials: 'include'` 또는 axios `withCredentials: true`).
- 오류 문의 시 응답 `X-Request-Id`, 호출 시각, HTTP 상태, `code`를 공유한다. 토큰/쿠키/꿈 원문은 공유 로그에서 제외한다.
- `www.mongle.art`와 `api.mongle.art`는 Origin이 다르므로 CORS가 필요하다. 같은 사이트의 HTTPS 서브도메인 구성이다.
- localhost/Vercel 미리보기 도메인에서 운영 API를 호출하는 환경은 현재 www 운영 구성과 다르다. 허용 Origin과 쿠키 정책을 따로 검증한다. 임의의 모든 Vercel 도메인을 허용하지 않는다.

## 카카오 로그인과 온보딩

서비스 정책은 **카카오 로그인만 제공**이다. 프론트에 Google 버튼을 추가하지 않는다. 현재 코드에 Google 관련 구현이 남아 있지만 기본 비활성이고 완전 제거는 후속 작업이다.

1. 로그인 버튼에서 아래 주소로 브라우저 전체 이동한다. fetch/axios로 인증 화면을 호출하지 않는다.

   `https://api.mongle.art/oauth2/authorization/kakao`

2. 카카오의 Redirect URI는 백엔드 `https://api.mongle.art/login/oauth2/code/kakao`이다. 프론트 완료 페이지와 다른 주소다.
3. 최신 #70 코드는 로그인 성공 시 HttpOnly Refresh 쿠키를 설정하고 설정된 프론트 완료 URL로303 이동한다. URL에 Access Token을 실어 보내지 않는다.
4. 완료 화면에서 `GET /api/v1/auth/csrf` → 응답 `data.headerName`, `data.token` 확보 → 그 헤더와 쿠키로 `POST /api/v1/auth/refresh`.
5. Refresh 응답의 `data.accessToken`으로 업무 API를 호출한다. `data.user.onboardingCompleted=false`면 닉네임 입력 화면으로 이동한다.
6. `POST /api/v1/users/me/onboarding`에 `{"nickname":"몽글러"}`를 Bearer 인증으로 보낸다. 닉네임은 한글·영문·숫자2~10자다. 성공 응답으로 프론트 회원 상태를 갱신한다. 서버가 현재 DB의 온보딩 상태를 확인하므로 완료 직후 별도의 Refresh는 필수가 아니다.
7. `GET /api/v1/users/me`로 현재 회원 상태를 확인한다.

**프론트 로그인 완료·실패 경로는 아직 확정 정보가 없다.** `/auth/callback`, `/login`은 제안 경로였으며 이미 구현/배포됐다고 가정하지 않는다. 프론트에서 실제 경로를 확정한 뒤 백엔드의 `MONGLE_OAUTH_SUCCESS_URL`, `MONGLE_OAUTH_FAILURE_URL`에 www 도메인의 정확한 절대 URL을 넣고 배포해야 한다.

Refresh는 앱 전체에서 한 번씩 실행되도록 묶는다. StrictMode·중복 인터셉터·여러 API의401 처리로 같은 Refresh 쿠키를 동시에 사용하지 않는다. 여러 탭도 갱신을 조율해야 한다. 갱신 실패 시 무한 재시도하지 않고 로그인으로 안내한다.

로그아웃: 새 CSRF 토큰/헤더와 쿠키를 포함해 `POST /api/v1/auth/logout` 호출 후 프론트 Access Token·회원 상태 제거. Refresh/CSRF 쿠키는 API 호스트의 HttpOnly 쿠키이므로 프론트 JS에서 직접 읽지 않는다.

로그인 실패는 설정된 실패 URL에 `?code=...`를 붙여303 이동한다. 허용 코드만 안내 문구로 매핑한다.

| code | 처리 |
|---|---|
| AUTH_401_1 | 제공사 사용자 정보 형식 오류 |
| AUTH_401_2 | 이메일 미제공 |
| AUTH_401_3 | 이메일 유효성/인증 조건 불충족 |
| AUTH_401_6 | 동의 취소·state 오류 등 인증 실패 |
| AUTH_503_1 | 서버 로그인 처리 장애 |

## 공통 응답·충돌·페이지

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청에 성공했습니다.",
  "data": {},
  "errors": []
}
```

실제 payload는 `data` 안에 있다. 실패는 `success=false`, `data=null`, HTTP 상태와 도메인 `code`를 함께 본다. 페이앱 서버 통보는 plain text 예외이며 프론트 API가 아니다.

- 401: 인증 누락/만료. 세션 갱신을 한 번 시도하고 실패하면 로그인 안내.
- 403: 권한/온보딩/CORS/CSRF 확인. 무조건 로그인 만료로 처리하지 않는다.
- 404: 없는 기록 또는 다른 회원의 기록. 다른 회원 데이터 존재를 추측하지 않는다.
- 409: revision 충돌·진행 중 작업·잔액 부족·결제 보류 등. `code`별로 처리하고 최신 상태를 다시 조회한다.
- 503: 외부 기능 비활성/미설정/Provider 장애 등이 포함된다. 클라이언트에서 생성/결제를 무한 재시도하지 않는다.

`revision`, `sourceRevision`, `generationVersion`, `imageVersion`, `folderRevision`, `compositionRevision`, `jobVersion`은 서로 다른 값이다. 응답 값을 해당 요청 필드에 그대로 전달하며 프론트가 임의로 증가시키지 않는다. 페이지 API마다 page/beforeId/cursor 방식이 다르므로 명세의 파라미터를 따른다. 커서는 해석·변조하지 않는다.

## 꿈 기록·분석·이야기

- 날짜는 `YYYY-MM-DD`, 한국 날짜 기준 미래 불가·날짜당 꿈1개. 원문 최대500자.
- 초안 자동저장 → 원문 작성 완료 → 감정 선택으로 기록 완성. 감정은 HAPPY/CALM/EXCITED/SAD/ANXIOUS/ANGRY/CONFUSED 중 중복 없이1~3개이며 대표 감정/순위는 없다.
- 빈 제목은 빈 상태로 유지한다. 제목 최대20자이며 AI가 자동으로 제목을 채우는 계약이 아니다.
- 감정 선택 완료 후 자동 분석→서사 작업을 예약한다. 기록 저장 성공과 AI 작업 완료를 구분하고 `/generation`을 조회한다.
- 생성 상태와 실패 사유를 표시하고 성공한 분석/서사는 전용 GET으로 읽는다. 수정 후 이전 결과가 남을 수 있으므로 변경 안내를 함께 표시한다.
- 수정 전 `/mutation-status`, 영구 삭제 전 `/world-impacts`를 조회할 수 있다. 조회는 잠금이 아니므로 실제 변경 시409가 올 수 있다.
- 삭제는 영구 삭제다. 관련 개별 분석/서사/이미지와 세계관 영향이 있으며 복구 API는 없다. 이미지 파일의 물리 삭제는 정리 워커와 별도다.

## 개별 이미지와 생성 상태

- `GET /api/v1/image-options`의 실제 옵션 키를 사용한다. 화면 문구를 임의 style 문자열로 보내지 않는다.
- `POST /api/v1/dreams/{dreamId}/image`에 최신 `revision`, `style`, 선택 `mood`, `regenerate`를 보낸다. 명시적 재생성이면 최신 `imageVersion`도 필요하다.
- Swagger의 `regenerationVersionProvided`는 서버 검증용 파생 속성이므로 입력으로 보내지 않는다.
- 현재 생성 API는 비동기 내부 처리를 사용하지만 요청이 실제 생성 완료까지 기다릴 수도 있다. 모든 생성 요청이 즉시202를 반환한다고 가정하지 않는다. 202/PROCESSING이면 상태를 조회한다.
- 브라우저 타임아웃/취소는 서버 생성 취소나 환급 확정을 의미하지 않는다. 다시 상태를 확인한다. 공통15초 timeout을 그대로 쓰면 이미지/외부 요청이 먼저 끊길 수 있으므로 기능별 대기·폴링을 조율한다.
- `FAILED`라도 `hasPreviousResult=true`이면 이전 성공 이미지를 유지한다. `sourceChanged`, `storyChanged`와 결과 revision을 안내한다.
- 실제 이미지 URL은 `/images/{imageId}/download-url`에서 발급받는다. 만료되는 서명 URL이며 저장소 영구 URL처럼 취급하지 않는다. `sourceDeleted` 등 접근 상태도 확인한다.

**확정됐지만 아직 코드에 반영되지 않은 정책:** 스타일4개, 꿈/세계관별 첫 성공3토큰→다음 성공1회 무료→이후 성공마다1토큰. 현재 API에 무료 잔여 횟수/다음 차감량 필드는 없다. 이를 프론트 카운트만으로 구현하거나 현재 서버가 이미 이 가격을 적용한다고 안내하지 않는다. 같은 대상이면 원문·스타일·세계관 구성이 바뀌어도 권리는 유지하고, 실패는 차감/무료 횟수를 소비하지 않도록 후속 구현한다.

## 세계관 폴더와 완성 이야기

- 폴더 CRUD·꿈 선택/제외·페이지 조회는 구현돼 있다. 폴더 이름은 현재 최대100 Unicode 코드포인트이고 꿈 선택 수의 별도 상한은 기본 설정하지 않는다.
- 현재 폴더 선택과 저장된 완성본은 별개다. 폴더의 `revision`과 완성본의 `compositionRevision`을 구분한다.
- 완성본 생성은 꿈2개 이상에서 요청한다. 최초 `compositionRevision=0`; 최신 `folderRevision`과 함께 보낸다. 202 후 `/composition` 상태를 조회한다.
- `hasResult=true`면 생성 중/실패에도 이전 완성본을 유지한다. 항목은 `/composition/entries`로 페이지 조회한다.
- 원문/새 서사는 자동 반영되지 않는다. `selectionChanged/sourceChanged/newStoryAvailable/hasConnectionGaps`로 변경 안내하고 필요한 생성/부분 반영을 요청한다.
- 꿈 삭제로 남은 꿈이0~1개여도 폴더와 기존 유효 결과는 보존한다. 새로운 전체 구성은2개 조건을 따른다. 삭제 후 새 연결을 자동으로 생성하지 않는다.
- 폴더/꿈 연결/이야기 구성은 무료다. **세계관 전체 이미지 API는 현재55개 명세에 없고 별도 #71 범위다.**

## 크레딧·페이앱

상품 목록 → 주문 생성 → paymentUrl → 결제 후 주문 상태 재조회 → 잔액/원장 갱신 순서로 연결한다.

```json
{
  "requestId": "2233e4d7-992c-48e3-b519-334fc754e925",
  "productId": "상품목록에서 받은 실제 productId",
  "buyerPhone": "01012345678"
}
```

- 금액·지급량은 서버 상품을 따른다. 프론트에서 가격/크레딧/회원 ID를 주문에 보내지 않는다.
- 상품 정책은10토큰1,100원/KRW로 확정됐지만 현재 코드의 운영 기본 상품은 빈 목록이다. 실제 운영 상품 설정과 활성화가 필요하다.
- `requestId`는 결제 버튼 한 번의 UUID이며 같은 구매 요청 재전송 시 유지한다.
- READY의 paymentUrl은 결제창 주소이며 결제/지급 완료가 아니다. 프론트 복귀·성공 이벤트로 잔액을 임의 증가시키지 않는다.
- REQUESTING/UNKNOWN은 결과 불명확 상태다. 새 UUID로 자동 구매를 다시 만들지 않는다.
- PAID와 `creditGranted`를 확인한다. 지급은 검증된 페이앱 서버 통보 후 확정된다.
- PAYMENT_REVIEW 또는 잔액 `spendingBlocked=true`이면 구매/사용을 막고 확인 안내를 표시한다.
- `POST /api/v1/payments/payapp/feedback`은 페이앱 서버 전용 FORM 통보이며 프론트에서 호출하지 않는다.
- 잔액은 available/reserved/total 및 보류 여부를 구분한다. 신규 회원 기본0, 크레딧 무기한. 구매 취소와 생성 실패 예약 해제는 다른 처리다.
- 구매 환불 신청·자동 회수/보류 해제 API는 아직 없다. 법정 환불 접수·운영 취소 처리 경로는 별도 준비 중이며 일괄 환불 불가를 확정한 것은 아니다.

## 현재 없는 API와 운영 미검증 범위

| 항목 | 현재 상태 |
|---|---|
| 이미지3→0→1 과금/무료권/다음 차감량 | 후속 구현 필요 |
| 스타일4개 고정 카탈로그·프롬프트 | 후속 구현 필요, 모델/옵션 검증 상준 담당 |
| 세계관 전체 이미지 | #71 별도 구현 범위 |
| 무료 종합 해석 생성/저장/갱신 | 미구현. recent-context는 최근 분석 대상 조회다 |
| Google 완전 제거 | 정책 확정, 현재 기본 비활성이고 잔여 코드 정리 필요 |
| 회원탈퇴/카카오 연결 해제 | 미구현 |
| 실결제·AI·이미지 | API/어댑터 구현과 실제 계정 활성화·실검증을 구분 |

종합 해석은 첫 진입 자동 생성·동일 대상 재사용·변경 안내 후 버튼 갱신·무료로 확정됐지만 현재 조회 API를 생성 API로 사용하지 않는다. 실물 상품·광고 보상은 이번 MVP 대상이 아니다.

운영의 AI/이미지/S3/크레딧/결제/워커는 마지막 인계에서 비활성이다. 배포 전용 S3가 만들어졌다고 이미지용 S3까지 구성된 것은 아니다. 플래그와 실계정 검증 결과를 기능별로 별도 공유한다.

## 프론트에서 함께 확정할 정보

1. 실제 배포된 카카오 로그인 완료·실패 경로.
2. 결제 후 실제 프론트 복귀 경로. 복귀 URL의 orderId 전달을 추측하지 말고 요청 때 받은 orderId로 복구할 흐름을 함께 정한다.
3. 완료 화면의 CSRF→Refresh·온보딩·401 갱신 공통 처리 및 긴 생성 요청의 상태 복구.
4. 남은 기능 API가 추가될 때 이 명세를 갱신하는 시점. 현재 JSON은 먼저 구현된 기능 연동용이며 최종 MVP 전체 명세가 아니다.

## 현재55개 API 목록

요청 본문·필수값·쿼리·응답 스키마는 함께 전달한 JSON을 기준으로 한다. OAuth 시작/콜백과 health/Swagger는 이 업무 API 집계에 포함하지 않는다.

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/analyses/{analysisId}` | 내 분석 조회 |
| GET | `/api/v1/analysis-records/monthly` | 월별 꿈 요소 및 선택 감정 통계 |
| GET | `/api/v1/analysis-records/recent-context` | 최근 꿈 해석 대상 조회 |
| GET | `/api/v1/archives` | Archive 목록 조회 |
| GET | `/api/v1/archives/calendar` | 꿈 캘린더 월별 조회 |
| GET | `/api/v1/archives/{dreamId}` | Archive 상세 조회 |
| GET | `/api/v1/auth/csrf` | CSRF 토큰 조회 |
| POST | `/api/v1/auth/logout` | 현재 로그인 세션 로그아웃 |
| POST | `/api/v1/auth/refresh` | 인증 토큰 재발급 |
| GET | `/api/v1/credits/balance` | 내 크레딧 잔액 |
| GET | `/api/v1/credits/transactions` | 내 크레딧 이용 내역 |
| POST | `/api/v1/dreams` | 꿈 원문 등록 |
| PUT | `/api/v1/dreams/drafts/{dreamedAt}` | 입력 중 꿈 자동저장 |
| GET | `/api/v1/dreams/incomplete` | 미완성 꿈 복구 목록 |
| DELETE | `/api/v1/dreams/{dreamId}` | 내 꿈 삭제 |
| GET | `/api/v1/dreams/{dreamId}` | 내 꿈 단건 조회 |
| PATCH | `/api/v1/dreams/{dreamId}` | 완성한 꿈 수정 |
| POST | `/api/v1/dreams/{dreamId}/analysis` | 완성 꿈 장면 구조화 |
| PUT | `/api/v1/dreams/{dreamId}/emotions` | 감정 선택 및 기록 완성 |
| GET | `/api/v1/dreams/{dreamId}/generation` | 자동 생성 진행 상태 조회 |
| POST | `/api/v1/dreams/{dreamId}/generation/regenerate` | 최신 입력으로 분석·서사 명시적 재생성 |
| POST | `/api/v1/dreams/{dreamId}/generation/retry` | 실패한 자동 생성 단계 재시도 |
| GET | `/api/v1/dreams/{dreamId}/image` | 꿈의 이미지 상태 조회 |
| POST | `/api/v1/dreams/{dreamId}/image` | 꿈 이미지 생성·재생성 |
| GET | `/api/v1/dreams/{dreamId}/mutation-status` | 꿈 수정·삭제 허용 상태 조회 |
| GET | `/api/v1/dreams/{dreamId}/story` | 꿈의 이야기 조회 |
| POST | `/api/v1/dreams/{dreamId}/story` | 완성 꿈 이야기 생성·재생성 |
| POST | `/api/v1/dreams/{dreamId}/submit` | 원문 작성 완료 |
| GET | `/api/v1/dreams/{dreamId}/world-impacts` | 꿈 영구 삭제의 세계관 영향 조회 |
| GET | `/api/v1/image-options` | 허용 이미지 옵션 조회 |
| GET | `/api/v1/images/{imageId}` | 내 이미지 단건 조회 |
| GET | `/api/v1/images/{imageId}/download-url` | 내 이미지 임시 다운로드 URL 발급 |
| GET | `/api/v1/payments/orders` | 내 크레딧 구매 주문 내역 |
| POST | `/api/v1/payments/orders` | 크레딧 충전 주문 생성 |
| GET | `/api/v1/payments/orders/{orderId}` | 내 결제 주문 상태 조회 |
| POST | `/api/v1/payments/payapp/feedback` | 페이앱 결제 통보 |
| GET | `/api/v1/payments/products` | 크레딧 충전 상품 목록 |
| GET | `/api/v1/stories/{storyId}` | 내 이야기 단건 조회 |
| GET | `/api/v1/stories/{storyId}/versions` | 내 이야기의 성공 버전 목록 |
| GET | `/api/v1/stories/{storyId}/versions/{versionId}` | 내 이야기의 특정 성공 버전 조회 |
| GET | `/api/v1/users/me` | 내 정보 조회 |
| POST | `/api/v1/users/me/onboarding` | 닉네임 온보딩 완료 |
| GET | `/api/v1/worlds` | 내 세계관 폴더 목록 |
| POST | `/api/v1/worlds` | 세계관 폴더 생성 |
| DELETE | `/api/v1/worlds/{worldId}` | 세계관 폴더 삭제 |
| GET | `/api/v1/worlds/{worldId}` | 세계관 폴더 상세 |
| PATCH | `/api/v1/worlds/{worldId}` | 세계관 폴더 이름 수정 |
| GET | `/api/v1/worlds/{worldId}/composition` | 세계관 완성본·작업 상태 조회 |
| POST | `/api/v1/worlds/{worldId}/composition` | 세계관 완성본 생성 |
| POST | `/api/v1/worlds/{worldId}/composition/dreams/{dreamId}/apply` | 세계관에 새 서사 부분 반영 |
| GET | `/api/v1/worlds/{worldId}/composition/entries` | 완성 세계관 구성 페이지 조회 |
| POST | `/api/v1/worlds/{worldId}/composition/retry` | 실패한 세계관 작업 재시도 |
| GET | `/api/v1/worlds/{worldId}/dreams` | 선택한 꿈 페이지 조회 |
| DELETE | `/api/v1/worlds/{worldId}/dreams/{dreamId}` | 세계관에서 꿈 제외 |
| PUT | `/api/v1/worlds/{worldId}/dreams/{dreamId}` | 세계관에 꿈 선택 |

## 로컬 실행

Java21 환경의 서버 저장소에서 다음을 실행한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

http://localhost:8080/swagger-ui.html 을 연다. 문서 열람에 비밀 키가 필요하지 않는다. local은 H2 메모리 DB이며 종료 시 데이터가 사라진다. 실제 업무 호출에는 로그인 토큰이 필요하고 임시 토큰 발급 우회 API는 없다. 카카오 로컬 로그인은 별도의 local,oauth 설정과 localhost 콜백/프론트 경로가 필요하다.

세부 구현: [카카오 로그인](https://github.com/mongle-web/mongle-server/blob/develop/docs/kakao-oauth-web.md), [개별 이미지](https://github.com/mongle-web/mongle-server/blob/develop/docs/dream-image.md), [세계관 구성](https://github.com/mongle-web/mongle-server/blob/develop/docs/world-composition.md), [페이앱](https://github.com/mongle-web/mongle-server/blob/develop/docs/payapp-payments.md). 문서의 과거 예시보다 이 전달본의 확정 www 주소와 최신 코드/정책 상태를 우선한다.
