# 몽글 프론트엔드 연동 전달본

2026-10-29 백엔드 코드 기준. 구현된 API와 확정 정책의 프론트 연동 계약을 정리한다. 각 기능의 사용 가능 여부와 최신 명세는 실제 배포 커밋·운영 설정·Swagger를 기준으로 확인한다. **코드 구현과 운영 활성 상태를 구분하며, 이 문서 수정만으로 운영 배포·외부 서비스 검증을 완료한 것으로 보지 않는다.**

## 주소와 명세 전달

| 용도 | 주소/상태 |
|---|---|
| 프론트 대표 주소 | https://www.mongle.art |
| API 기본 주소 | https://api.mongle.art |
| 공통 API 접두사 | /api/v1 |
| 운영 health | https://api.mongle.art/actuator/health — 아래 과거 운영 확인 기록 참고 |
| 운영 Swagger | https://api.mongle.art/swagger-ui.html — 아래 과거 운영 확인 기록 참고 |
| 운영 OpenAPI JSON | https://api.mongle.art/v3/api-docs — 실제 배포 커밋의 명세 확인 |
| 로컬 Swagger | http://localhost:8080/swagger-ui.html |
| 로컬 OpenAPI JSON | http://localhost:8080/v3/api-docs |

기존 2026-10-29 운영 확인 기록은 #90 merge commit `3dceeee7333d1a8bf89631d0961c4c972cfd64df` 기준이다. 당시 CI40826537314/CD40826960919 성공과 외부 health·OpenAPI200(49경로·61개 API)을 확인했다. 이후 #93 가입 지급·#98 검색 등의 실제 운영 적용은 배포 커밋과 플래그를 별도로 확인한다. 실제 카카오·업무 기능의 브라우저 검증은 별도다.

공통 설정에서는 문서가 기본 비활성이며 위 과거 운영 확인 시에는 `SPRINGDOC_API_DOCS_ENABLED=true`, `SPRINGDOC_SWAGGER_UI_ENABLED=true`로 공개했다. 문서 열람과 실제 기능 활성화·로그인 성공은 구분한다.

우선 별도 첨부하는 **프론트 공유용 OpenAPI JSON**을 Postman 등의 Import 기능으로 사용한다. JSON의 운영 주소는 호출 대상 설정이며, 모든 기능이 운영에서 활성화됐다는 의미가 아니다. 원본 추출 JSON의 `127.0.0.1:18179`은 임시 추출 서버이며 공유 서버가 아니다.

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

서비스 정책과 #79 코드는 **카카오 로그인만 제공**이다. 프론트에 Google 버튼을 추가하지 않는다. Google 로그인 등록·OIDC 처리·계정 제공자 enum은 #79에서 제거했다.

1. 로그인 버튼에서 아래 주소로 브라우저 전체 이동한다. fetch/axios로 인증 화면을 호출하지 않는다.

   `https://api.mongle.art/oauth2/authorization/kakao`

2. 카카오의 Redirect URI는 백엔드 `https://api.mongle.art/login/oauth2/code/kakao`이다. 프론트 완료 페이지와 다른 주소다.
3. 최신 #70 코드는 로그인 성공 시 HttpOnly Refresh 쿠키를 설정하고 설정된 프론트 완료 URL로303 이동한다. URL에 Access Token을 실어 보내지 않는다.
4. 완료 화면에서 `GET /api/v1/auth/csrf` → 응답 `data.headerName`, `data.token` 확보 → 그 헤더와 쿠키로 `POST /api/v1/auth/refresh`.
5. Refresh 응답의 `data.accessToken`으로 업무 API를 호출한다. `data.user.onboardingCompleted=false`면 닉네임 입력 화면으로 이동한다.
6. `POST /api/v1/users/me/onboarding`에 `{"nickname":"몽글러"}`를 Bearer 인증으로 보낸다. 닉네임은 한글·영문·숫자2~10자다. 최초 성공 시 가입 혜택 기본 3크레딧도 함께 저장한다. 프론트 회원 상태를 갱신하고 잔액 API를 다시 조회한다. 로컬 잔액에 임의로3을 더하지 않는다. 같은 닉네임 재요청은 추가 지급하지 않으며 기존 완료 계정에는 소급하지 않는다. 크레딧 비활성/가입 지급량 미준비는503 CREDIT_503_1이고 온보딩도 롤백하므로 완료 화면으로 넘어가지 않는다. 서버가 현재 DB의 온보딩 상태를 확인하므로 완료 직후 별도의 Refresh는 필수가 아니다.
7. `GET /api/v1/users/me`로 현재 회원 상태를 확인한다.

백엔드 운영 복귀 주소는 성공 `https://www.mongle.art/auth/callback`, 실패 `https://www.mongle.art/login`으로 설정됐다는 인계를 받았다. 프론트의 실제 페이지 구현·배포와 계정 로그인 종단 검증은 별도로 확인한다. 카카오 콘솔 Redirect URI는 백엔드 Callback을 유지한다.

Refresh는 앱 전체에서 한 번씩 실행되도록 묶는다. StrictMode·중복 인터셉터·여러 API의401 처리로 같은 Refresh 쿠키를 동시에 사용하지 않는다. 여러 탭도 갱신을 조율해야 한다. 쿠키 인증 요청에는 공통 인터셉터가 만료된 Bearer 토큰을 덧붙이지 않도록 분리한다. 갱신 실패 시 무한 재시도하지 않고 로그인으로 안내한다.

로그아웃: 새 CSRF 토큰/헤더와 쿠키를 포함해 `POST /api/v1/auth/logout` 호출 후 프론트 Access Token·회원 상태 제거. Refresh/CSRF 쿠키는 API 호스트의 HttpOnly 쿠키이므로 프론트 JS에서 직접 읽지 않는다.

로그인 실패는 설정된 실패 URL에 `?code=...`를 붙여303 이동한다. 허용 코드만 안내 문구로 매핑한다.

| code | 처리 |
|---|---|
| AUTH_401_1 | 제공사 사용자 정보 형식 오류 |
| AUTH_401_2 | 이메일 미제공 |
| AUTH_401_3 | 이메일 유효성/인증 조건 불충족 |
| AUTH_401_6 | 동의 취소·state 오류 등 인증 실패 |
| AUTH_503_1 | 서버 로그인 처리 장애 |

## 마이페이지 닉네임 변경

`PATCH /api/v1/users/me/nickname`에 Bearer 인증과 JSON `{"nickname":"새몽글"}`을 보낸다.
온보딩을 마친 본인만 가능하며, 한글·영문·숫자2~10자와 중복 닉네임 허용 정책은 기존 온보딩과 같다.
앞뒤 공백도 유효하지 않으며 사용자 ID·이메일 등 다른 필드를 보내지 않는다.

성공200의 `data`는 `UserResponse`이며 변경된 nickname으로 프론트 회원 상태를 갱신한다.
같은 닉네임 재요청도200이고 수정 시각을 바꾸지 않는다. 변경 전 Access Token도 계속 사용할 수 있으며
별도 로그인·Refresh 없이 `GET /api/v1/users/me`에서 새 값을 조회한다.
가입 크레딧·원장·온보딩 상태는 유지하고 재지급하지 않는다. 크레딧 기능이 비활성이어도 닉네임 변경은 가능하다.

잘못된 nickname은400 `COMMON_400_2`, 다른 필드/잘못된 본문은400 `COMMON_400_1`,
온보딩 전 변경은403 `USER_403_1`이다. 최초 가입은 기존 `/onboarding` API를 사용한다.
별도 닉네임 중복 확인 API는 없다. [상세 계약](nickname-update.md)을 참고한다.

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
- 제목은 최대20 코드포인트다. 분석 성공 시 같은 sourceRevision·title=null·edited=false인 꿈에만 AI 생성 제목을 저장한다. 사용자가 정한 제목이나 실제로 비운 제목은 덮어쓰지 않는다. 화면은 `DreamResponse.title` 또는 Archive 카드의 `title`을 사용하고, 분석의 `generatedTitle`을 대체 제목으로 쓰지 않는다. 자동 제목 반영으로 revision이 바뀔 수 있어 생성 후 최신 revision을 다시 조회한다.
- 감정 선택 완료 후 자동 분석→서사 작업을 예약한다. 기록 저장 성공과 AI 작업 완료를 구분하고 `/generation`을 조회한다.
- 생성 상태와 실패 사유를 표시하고 성공한 분석/서사는 전용 GET으로 읽는다. 수정 후 이전 결과가 남을 수 있으므로 변경 안내를 함께 표시한다.
- 완료 기록도 제목·원문·감정을 PATCH로 수정할 수 있다. 미전달 필드는 유지하고 원문·감정 변경은 AI를 자동 호출하지 않는다. 최신 입력으로 분석·서사를 다시 만들려면 `POST /dreams/{dreamId}/generation/regenerate`에 최신 `revision`, `generationVersion`을 전달한다. 실패·진행 중에는 이전 성공을 보존한다. 성공해도 세계관은 자동 변경하지 않는다.
- 수정 전 `/mutation-status`, 영구 삭제 전 `/world-impacts`를 조회할 수 있다. 조회는 잠금이 아니므로 실제 변경 시409가 올 수 있다.
- 삭제는 영구 삭제다. 관련 개별 분석/서사/이미지와 세계관 영향이 있으며 복구 API는 없다. 이미지 파일의 물리 삭제는 정리 워커와 별도다.

## 보관함 검색

기존 목록 API의 선택적 `query`를 사용한다. 응답 DTO와 페이지 크기 기본20·최대50은 유지한다.

| 탭 | 요청 예시 | 검색 대상 | 다음 페이지 |
|---|---|---|---|
| 기록한 꿈 | `GET /api/v1/archives?query=바다&sort=LATEST&size=20` | 현재 제목 또는 원문 또는 마지막 저장된 표시 키워드 | 같은 query·month/date·sort와 nextCursor |
| 연결된 꿈 | `GET /api/v1/worlds?query=바다&size=20` | 폴더 이름만 | 같은 query와 nextBeforeId를 beforeId에 전달 |

- 검색어 앞뒤 Unicode 공백을 제거하며 최대50 코드포인트다. 생략·빈 문자열·공백만이면 전체 목록이다. 영문 대소문자를 구분하지 않고 부분 일치하며 `%`, `_`, `!`도 문자 그대로 검색한다. URL 쿼리는 URLSearchParams 등으로 인코딩한다.
- 본인 데이터만 조회한다. 기록한 꿈은 기존처럼 COMPLETED만 포함하며, 연결된 꿈은 선택 수0~1개인 폴더도 포함한다. 폴더 안의 꿈 원문·공통 키워드·반복 감정은 검색하지 않는다.
- 검색어 변경 시 기존 목록과 cursor/beforeId를 초기화한다. 기록한 꿈의 다른 검색어·날짜·정렬 커서는 `400 ARCHIVE_400_2`다. 폴더 beforeId는 검색어를 담지 않는 숫자이므로 프론트가 초기화한다. 늦게 도착한 이전 검색 응답이 현재 검색 목록을 덮어쓰지 않도록 요청을 구분한다.
- 결과 없음은200, `items=[]`, `hasNext=false`, 다음 경계는null이다. 화면에서 '기록 없음'과 '검색 결과 없음'을 구분한다.
- 50자 초과는 기록한 꿈 `ARCHIVE_400_5`, 폴더 `WORLD_400_4`다. 검색은 AI·이미지 저장소·크레딧을 호출하지 않는다.
- 수정한 제목·원문은 즉시 검색된다. 원문 수정 후 키워드는 재생성 전까지 이전 값을 유지하므로 `analysis.sourceChanged` 안내를 함께 표시한다.

## 개별 이미지와 생성 상태

- `GET /api/v1/image-options`의 실제 옵션 키를 사용한다. 기본 화풍은 없으므로 사용자가 선택해야 한다. 현재 기본 코드는 `surrealism`, `geometric-cubism`, `expressive-brushwork`, `modern-pop-art`이며 한글 이름·설명·썸네일은 이 응답에 없다. 표시 정보와 기법 매핑은 [화풍 정책](image-generation-policy.md)을 따른다. 옵션 목록이 있다고 실제 생성 가능함을 보장하지는 않는다.
- `POST /api/v1/dreams/{dreamId}/image`에 최신 `revision`, 선택한 `style`, `regenerate`를 보낸다. 명시적 재생성이면 최신 `imageVersion`도 필요하다. 현재 기본 `moods=[]`이므로 `mood`는 생략하거나 null로 보내며 빈 문자열은 보내지 않는다. 기록한 꿈의 선택 감정과 이미지 mood는 별개다.
- Swagger의 `regenerationVersionProvided`는 서버 검증용 파생 속성이므로 입력으로 보내지 않는다.
- 현재 생성 API는 비동기 내부 처리를 사용하지만 요청이 실제 생성 완료까지 기다릴 수도 있다. 모든 생성 요청이 즉시202를 반환한다고 가정하지 않는다. 202/PROCESSING이면 상태를 조회한다.
- 브라우저 타임아웃/취소는 서버 생성 취소나 환급 확정을 의미하지 않는다. 다시 상태를 확인한다. 공통15초 timeout을 그대로 쓰면 이미지/외부 요청이 먼저 끊길 수 있으므로 기능별 대기·폴링을 조율한다.
- `FAILED`라도 `hasPreviousResult=true`이면 이전 성공 이미지를 유지한다. `sourceChanged`, `storyChanged`와 결과 revision을 안내한다.
- 실제 이미지 URL은 `/images/{imageId}/download-url`에서 발급받는다. 만료되는 서명 URL이며 저장소 영구 URL처럼 취급하지 않는다. `sourceDeleted` 등 접근 상태도 확인한다.
- 새 파일로 교체되면 이전 파일은 같은 트랜잭션에서 정리 요청을 등록하고 워커가 삭제한다. 실패·만료에는 기존 성공 파일을 보존하며 파일 이력 목록 API는 제공하지 않는다.

꿈·세계관 각각 기본 최초 생성3크레딧, 첫 재생성 성공1회 무료, 이후 재생성1크레딧이다. 유료 비용은 서버 설정으로 변경할 수 있다. 무료 이력은 꿈별·세계관별이며 화풍·원문·감정·구성 변경으로 초기화되지 않는다. 무료도 결과 저장 성공 시에만 소진하고 실패·만료·롤백에는 유지한다. [가격·배포 계약](image-generation-policy.md)을 따른다.

이미지 응답의 `freeRegenerationAvailable`은 무료 이력 안내, `nextRegenerationCost`는 현재 이력·가격 설정에 따른 다음 재생성 비용이다. 0일 때만 무료로 표시한다. null은 성공 결과가 없거나 재생성 가격이 미설정이라는 뜻이다. 기존 성공이 있어도 가격 미설정이면 무료·유료 신규 재생성을 `CREDIT_503_1`로 거절한다. 무료 이력이 있어도 생성 입력·진행 작업·기능 활성화·결제 보류 등 실행 조건은 별도로 검사한다. 최초 생성 비용·설정상 사용 가능 여부는 image-options에 포함되어 있지 않다.

## 세계관 AI 제목·공통 키워드·반복 감정 (#95)

- `GET /api/v1/worlds/{worldId}/summary`: 상태와 `generatedTitle`, `commonKeywords[{keyword,dreamCount}]`, `recurringEmotions[{code,label,dreamCount}]` 조회.
- `POST /api/v1/worlds/{worldId}/summary`: 본문 없이 최초 생성/실패 재시도. 202는 접수/진행, 200은 완료 재사용/출처 없음이다. `ApiResponse.data.status`를 확인한다.
- 세계관 완성·부분 반영 성공 시 자동 접수한다. 기존 완성본이 `NOT_GENERATED`이면 POST로 생성한다. GET은 AI를 호출하지 않는다. 생성 중 상태는 GET으로 조회한다.
- 폴더 `name`과 결과 `generatedTitle`은 별개다. 폴더 이름을 AI 제목으로 자동 덮어쓰지 않는다. 키워드/감정은 고정 서사 버전의 스냅샷에서 서로 다른 꿈 2개 이상에 실제 존재하는 항목만 반환하며 근거가 없으면 빈 배열이다.
- `refreshNeeded`·`sourceChanged`로 변경 안내, `hasPreviousResult`로 생성 중/실패 시 이전 결과 표시 여부를 판단한다. 원문만 바뀌면 먼저 새 서사를 세계관에 반영한다. 같은 완성본 POST 반복은 결과를 재사용한다. 삭제된 꿈의 텍스트 결과는 제거한다.
- 무료이며 크레딧/이미지 생성 요청과 독립적이다. V5 마이그레이션·코드 배포 후 `MONGLE_WORLD_SUMMARY_ENABLED=true`, `MONGLE_WORLD_SUMMARY_WORKER_ENABLED=true`와 기존 LINER 설정이 필요하다. 이번 코드 작업에서 운영 활성화는 수행하지 않았다.
- 전체 상태·필드·실패 처리·활성화 순서는 [세계관 결과 상세](world-summary.md)를 참고한다. 운영 Swagger는 실제 배포된 버전으로 확인한다.

## 연결된 꿈 목록 카드 (#103)

`GET /api/v1/worlds`의 items에 기존 폴더 필드와 함께 다음 값을 제공한다. 응답 봉투·검색·페이지 경계는 유지한다.

| 화면 정보 | 필드 |
|---|---|
| 카드 제목 | summary.generatedTitle, 없으면 name |
| 대표 꿈·날짜 | representativeDream.dreamId / title / dreamedAt. 공개 완성본의 가장 오래된 꿈이며 없으면null |
| 현재 연결 개수 | composedDreamCount. 생성 중 후보·체크한 꿈 수와 구분 |
| 키워드 태그 | summary.commonKeywords[].keyword |
| 반복 감정·상태·변경 안내 | 기존 WorldSummaryResponse와 같은 summary 필드 |

연결 설명은 대표 꿈 제목과 composedDreamCount를 사용한다. 총5개면 '대표 꿈 제목 외 4개의 꿈을 연결했어요'다.
1개·0개·제목 없음은 별도 문구를 정한다. summary.sourceCount는 요약이 만들어질 당시의 출처 수이므로
현재 연결 개수로 사용하지 않는다. 이전 요약을 표시하면 refreshNeeded/sourceChanged/hasPreviousResult도 함께 안내한다.
미생성·생성 중·실패에는 유효한 기존 결과 또는 폴더 이름을 표시하고 공통 키워드가 없으면 태그를 보충하지 않는다.
목록 조회는 AI 호출·생성 접수·크레딧 차감·상태 저장이 없으며 삭제된 출처의 결과를 숨긴다. 단건 폴더·쓰기 응답은 기존 형식이다.
기존 query는 폴더 이름만 검색한다. [응답 예시·삭제/만료 규칙](world-list-cards.md)을 따른다.

## 세계관 폴더와 완성 이야기

- 폴더 CRUD·꿈 선택/제외·페이지 조회는 구현돼 있다. 폴더 이름은 현재 최대100 Unicode 코드포인트이고 꿈 선택 수의 별도 상한은 기본 설정하지 않는다.
- 현재 폴더 선택과 저장된 완성본은 별개다. 폴더의 `revision`과 완성본의 `compositionRevision`을 구분한다.
- 완성본 생성은 꿈2개 이상에서 요청한다. 최초 `compositionRevision=0`; 최신 `folderRevision`과 함께 보낸다. 202 후 `/composition` 상태를 조회한다.
- `hasResult=true`면 생성 중/실패에도 이전 완성본을 유지한다. 항목은 `/composition/entries`로 페이지 조회한다.
- 원문/새 서사는 자동 반영되지 않는다. `selectionChanged/sourceChanged/newStoryAvailable/hasConnectionGaps`로 변경 안내하고 필요한 생성/부분 반영을 요청한다.
- 새 서사를 반영할 세계관마다 `POST /worlds/{worldId}/composition/dreams/{dreamId}/apply`에 `folderRevision`, `compositionRevision`, `storyVersionId`를 보낸다. 대상 꿈과 앞뒤 연결만 갱신하며 다른 꿈·다른 세계관의 완성본은 유지한다. 같은 고정 버전은 변경 없이 재사용한다. [상세 계약](world-composition.md)을 따른다.
- 꿈 삭제로 남은 꿈이0~1개여도 폴더와 기존 유효 결과는 보존한다. 새로운 전체 구성은2개 조건을 따른다. 삭제 후 새 연결을 자동으로 생성하지 않는다.
- 폴더/꿈 연결/이야기 구성은 무료다. 세계관 전체 이미지는 #77의 생성·재생성/상태/다운로드3개 API를 통합했다. 이미지 사용권 정책과 운영 활성화는 별도다. #89부터 새 이미지 생성·재생성에는 완성본 출처의 서로 다른 꿈이 2개 이상 필요하며, 0~1개일 때는 WORLD_IMAGE_409_1로 거절한다. 기존 이미지 조회·다운로드는 유지한다. freeRegenerationAvailable은 무료 이력 안내이며 출처 개수에 따른 실행 허가를 뜻하지 않는다.

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
- 아래 결제 API는 구현된 크레딧 주문 계약이다. 이미지 과금은 위3/무료/1 정책을 따르며, 판매 상품 가격·지급량·환불 등 판매 조건과 실제 결제 활성화는 별도로 정한다. 현재 기본 상품은 빈 목록이다. 상품 목록 노출만으로 실결제 가능함을 보장하지 않는다.
- `requestId`는 결제 버튼 한 번의 UUID이며 같은 구매 요청 재전송 시 유지한다.
- READY의 paymentUrl은 결제창 주소이며 결제/지급 완료가 아니다. 프론트 복귀·성공 이벤트로 잔액을 임의 증가시키지 않는다.
- REQUESTING/UNKNOWN은 결과 불명확 상태다. 새 UUID로 자동 구매를 다시 만들지 않는다.
- PAID와 `creditGranted`를 확인한다. 지급은 검증된 페이앱 서버 통보 후 확정된다.
- PAYMENT_REVIEW 또는 잔액 `spendingBlocked=true`이면 구매/사용을 막고 확인 안내를 표시한다.
- `POST /api/v1/payments/payapp/feedback`은 페이앱 서버 전용 FORM 통보이며 프론트에서 호출하지 않는다.
- 잔액은 available/reserved/total 및 보류 여부를 구분한다. 최초 온보딩 완료 시 기본3크레딧, 크레딧 무기한. 지급 내역이 없는 기존 완료 계정은0이며 자동 소급하지 않는다. 구매 취소와 생성 실패 예약 해제는 다른 처리다.
- 구매 환불 신청·자동 회수/보류 해제 API는 아직 없다. 법정 환불 접수·운영 취소 처리 경로는 별도 준비 중이며 일괄 환불 불가를 확정한 것은 아니다.

## 현재 없는 API와 운영 미검증 범위

| 항목 | 현재 상태 |
|---|---|
| 이미지 크레딧 과금 | 최초3·첫 재생성 성공 무료·이후1 구현. 가격 미설정 시 무료 요청도 거절하며 실제 운영 활성화는 별도 |
| 판매 상품·기간 이용권·환불 | 판매 조건·상품 설정 확정과 실결제 검증 별도. 기간 이용권·환불 신청 API 없음 |
| 스타일4개 고정 카탈로그·프롬프트 | #86 구현·머지. 키·표시 이름은 docs/image-generation-policy.md 참고, 실제 생성 품질 검증 별도 |
| 세계관 전체 이미지 | #77 구현·통합. 정확한 SQL 적용과 운영 활성화·실검증은 별도 |
| 무료 종합 해석 생성/저장/갱신 | #84 구현. 기본 비활성, LINER 실검증 및 프론트 연동 필요 |
| Google 완전 제거 | #79 머지·운영 반영 완료 |
| 회원탈퇴/카카오 연결 해제 | 미구현 |
| 실결제·AI·이미지 | API/어댑터 구현과 실제 계정 활성화·실검증을 구분 |

종합 해석은 첫 진입 POST → 상태 GET → 변경 안내 후 명시적 refresh POST를 사용한다. 동일 대상 재사용·무료이며 recent-context 자체는 생성 API가 아니다. [상세 계약](recent-interpretation.md)을 따른다. 실물 상품·광고 보상은 이번 MVP 대상이 아니다.

2026-10-29 운영 담당자 공유 기준으로 페이앱 관련 기능을 제외한 AI·이미지·S3·크레딧·워커의 기존 활성화 설정은 켜둔 상태다. 비공개 환경 파일을 이번 코드 작업에서 직접 조회한 것은 아니며 실제 계정의 기능 검증은 별도로 확인한다. #95의 신규 세계관 결과 설정 두 개는 기존 플래그와 별개이므로 추가 여부를 확인한다. 버킷 생성·기능 활성화와 애플리케이션의 실제 저장·생성 성공은 구분한다.

## 프론트에서 함께 확정할 정보

1. 실제 배포된 카카오 로그인 완료·실패 경로.
2. 결제 후 실제 프론트 복귀 경로. 복귀 URL의 orderId 전달을 추측하지 말고 요청 때 받은 orderId로 복구할 흐름을 함께 정한다.
3. 완료 화면의 CSRF→Refresh·온보딩·401 갱신 공통 처리 및 긴 생성 요청의 상태 복구.
4. 남은 기능 API가 추가될 때 이 명세를 갱신하는 시점. 현재 JSON은 먼저 구현된 기능 연동용이며 최종 MVP 전체 명세가 아니다.

## 현재 API 목록

요청 본문·필수값·쿼리·응답 스키마는 함께 전달한 JSON을 기준으로 한다. OAuth 시작/콜백과 health/Swagger는 이 업무 API 집계에 포함하지 않는다.

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/analyses/{analysisId}` | 내 분석 조회 |
| GET | `/api/v1/analysis-records/monthly` | 월별 꿈 요소 및 선택 감정 통계 |
| GET | `/api/v1/analysis-records/recent-context` | 최근 꿈 해석 대상 조회 |
| GET | `/api/v1/analysis-records/interpretation` | 종합 해석·갱신 필요 여부 조회 |
| POST | `/api/v1/analysis-records/interpretation` | 첫 진입 종합 해석 생성 |
| POST | `/api/v1/analysis-records/interpretation/refresh` | 종합 해석 명시적 갱신 |
| GET | `/api/v1/archives` | Archive 목록·제목/원문/키워드 검색 |
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
| PATCH | `/api/v1/users/me/nickname` | 마이페이지 닉네임 변경 |
| POST | `/api/v1/users/me/onboarding` | 닉네임 온보딩 완료 |
| GET | `/api/v1/worlds` | 내 세계관 폴더 목록·이름 검색 |
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
| GET | `/api/v1/worlds/{worldId}/image` | 세계관 이미지 상태 조회 |
| POST | `/api/v1/worlds/{worldId}/image` | 세계관 이미지 생성·재생성 |
| GET | `/api/v1/worlds/{worldId}/image/download-url` | 세계관 이미지 다운로드 URL |
| GET | `/api/v1/worlds/{worldId}/summary` | 세계관 AI 제목·공통 키워드·반복 감정 및 상태 조회 |
| POST | `/api/v1/worlds/{worldId}/summary` | 세계관 결과 최초 생성·실패 재시도 접수 |

## 로컬 실행

Java21 환경의 서버 저장소에서 다음을 실행한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

http://localhost:8080/swagger-ui.html 을 연다. 문서 열람에 비밀 키가 필요하지 않는다. local은 H2 메모리 DB이며 종료 시 데이터가 사라진다. 실제 업무 호출에는 로그인 토큰이 필요하고 임시 토큰 발급 우회 API는 없다. 카카오 로컬 로그인은 별도의 local,oauth 설정과 localhost 콜백/프론트 경로가 필요하다.

세부 구현: [카카오 로그인](https://github.com/mongle-web/mongle-server/blob/develop/docs/kakao-oauth-web.md), [개별 이미지](https://github.com/mongle-web/mongle-server/blob/develop/docs/dream-image.md), [세계관 구성](https://github.com/mongle-web/mongle-server/blob/develop/docs/world-composition.md), [페이앱](https://github.com/mongle-web/mongle-server/blob/develop/docs/payapp-payments.md). 문서의 과거 예시보다 이 전달본의 확정 www 주소와 최신 코드/정책 상태를 우선한다.
