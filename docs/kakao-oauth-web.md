# 카카오 웹 로그인 연동

로그인은 카카오만 지원한다. 카카오 인증은 백엔드가 처리하고, 프론트는 로그인 시작과 완료/실패 화면을 담당한다. 기존 자체 JWT·Refresh 세션·CSRF·온보딩 계약을 유지한다.

## 배포 설정

```dotenv
SPRING_PROFILES_ACTIVE=mysql,oauth
OAUTH_BASE_URL=https://api.mongle.art
OAUTH_COOKIE_SECURE=true
MONGLE_OAUTH_KAKAO_ENABLED=true
KAKAO_CLIENT_ID=<REST-API-key>
KAKAO_CLIENT_SECRET=<Client-Secret>
MONGLE_AUTH_ALLOWED_ORIGINS=https://www.mongle.art
MONGLE_OAUTH_SUCCESS_URL=<확정된-프론트-완료-URL>
MONGLE_OAUTH_FAILURE_URL=<확정된-프론트-실패-URL>
MONGLE_AUTH_COOKIE_SAME_SITE=Lax
```

DB/TLS 5개 환경변수와 `MONGLE_AUTH_SECRET`은 [RDS 배포 안내](mysql-rds-deployment.md)를 따른다. 키·비밀번호·토큰은 Git/프론트 번들/로그에 넣지 않는다. 기존 서명 키는 유지한다. 새 DB 마이그레이션은 없다.

배포 전 `db/checks/kakao-only-preflight.sql`을 기존 TLS 연결로 읽기 전용 실행한다. `unsupported_provider_count=0`인지 확인한 뒤 머지/배포한다. `social_accounts`는 기존 VARCHAR(20)/VARBINARY(255) 구조를 유지하며, 초기 스키마 재적용이나 데이터 변환은 하지 않는다. 다른 제공자의 계정이 있다면 삭제·카카오 변환을 실행하지 말고 기존 데이터 처리부터 정한다. #79 준비 시 전달받은 운영 조회는 소셜 계정0건이었다.

완료·실패 URL은 필수 운영 설정이다. `/auth/callback`(완료), `/login`(실패)은 **프론트와 협의할 제안 경로**이며 현재 화면 구현/주소 확정을 뜻하지 않는다. 경로를 확정한 뒤 정확한 절대 URL을 설정한다. 예를 들어 완료 화면이 실제로 `https://www.mongle.art/auth/callback`에 생겼을 때만 그 값을 설정한다.

URL은 정확한 `MONGLE_AUTH_ALLOWED_ORIGINS` 중 하나에 속해야 하고, userinfo/query/fragment를 허용하지 않는다. 운영은 HTTPS를 요구한다. 요청의 `next`, `redirect_uri`, Host 등으로 프론트 목적지를 바꿀 수 없다. 실패 시 서버가 허용한 `code` 하나만 추가한다.

`oauth` 프로필에서는 `MONGLE_OAUTH_KAKAO_ENABLED=true`와 카카오 Client ID/Secret이 필요하다. 값이 없거나 미해결 placeholder이면 기동을 거절한다. `mysql`만 사용하는 첫 배포는 카카오 키 없이 기동할 수 있지만 로그인은 활성화되지 않는다.

이전 Google 활성화·키 설정은 더 이상 사용하지 않는다. 운영 파일에 남은 `MONGLE_OAUTH_GOOGLE_ENABLED`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`은 정리 대상이며 필수 설정이 아니다. 남아 있어도 Google 등록을 만들거나 로그인을 활성화하지 않는다. GitHub Actions 배포에 사용하는 AWS OIDC 설정은 이 변경과 별개다.

운영 `OAUTH_BASE_URL`은 경로·후행 슬래시 없는 백엔드 HTTPS Origin이다. 카카오 콘솔 Callback은 `https://api.mongle.art/login/oauth2/code/kakao`를 그대로 유지한다. 프론트 완료 페이지를 카카오 Callback으로 바꾸지 않는다.

## 브라우저 로그인 순서

1. 로그인 버튼은 `https://api.mongle.art/oauth2/authorization/kakao`로 **브라우저 전체 이동**한다. axios/fetch로 카카오 인증 화면을 요청하지 않는다.
2. 카카오 동의 후 백엔드 `/login/oauth2/code/kakao`가 인가 코드/state를 처리한다. OAuth용 JSESSIONID는 HttpOnly, SameSite=Lax, 운영 Secure이며 수명은 5분이다.
3. 백엔드는 회원 확인·Refresh 세션 발급 후 HttpOnly Refresh 쿠키를 설정하고 **303**으로 설정된 프론트 완료 페이지에 복귀한다. 토큰·인가 코드·회원 정보를 URL이나 Callback JSON에 넣지 않는다. OAuth 세션은 정리한다.
4. 완료 화면은 쿠키를 포함해 `GET /api/v1/auth/csrf`를 호출한다. 응답 `data.headerName`과 `data.token`을 사용해 `POST /api/v1/auth/refresh`를 호출한다. 이 요청도 쿠키를 포함한다.
5. 응답 `data.accessToken`으로 Bearer 인증을 시작한다. `data.user.onboardingCompleted=false`면 닉네임 온보딩, true면 홈으로 이동한다. 업무 API는 기존처럼 Bearer 인증을 사용한다.

예시(브라우저 완료 화면, API Origin은 실제 배포 설정에서 공급):

```javascript
let refreshing;

export function refreshSession(apiOrigin) {
  if (refreshing) return refreshing;
  refreshing = (async () => {
    const csrfResponse = await fetch(`${apiOrigin}/api/v1/auth/csrf`, {
      credentials: 'include', cache: 'no-store',
    });
    if (!csrfResponse.ok) throw new Error('CSRF 요청 실패');
    const { data: csrf } = await csrfResponse.json();
    const response = await fetch(`${apiOrigin}/api/v1/auth/refresh`, {
      method: 'POST', credentials: 'include', cache: 'no-store',
      headers: { [csrf.headerName]: csrf.token },
    });
    if (!response.ok) throw new Error('로그인 필요');
    return (await response.json()).data;
  })().finally(() => { refreshing = undefined; });
  return refreshing;
}
```

실제 앱에서는 기존 인증 상태 관리와 함께 사용한다. 완료 화면 마운트와 API 401 처리에서 같은 갱신 함수를 공유하고, StrictMode/인터셉터에서 Refresh를 병렬 호출하지 않는다. 서버는 이전 Refresh 토큰 재사용을 탐지하면 세션을 폐기한다. 여러 탭을 동시에 지원한다면 탭 간 갱신도 조율해야 한다. 예시는 프론트에 반영된 코드가 아니다.

Refresh 응답은 `{ success, code, message, data: { accessToken, tokenType, expiresIn, user: { userId, email, nickname, onboardingCompleted } } }` 형식이다. CSRF와 Refresh 쿠키는 JavaScript에서 읽지 않는다. CSRF 토큰은 조회 API의 JSON으로 받는다. axios를 쓰면 해당 인증 요청에 `withCredentials: true`가 필요하다.

로그아웃은 같은 쿠키+CSRF 헤더로 `POST /api/v1/auth/logout`을 호출한 뒤 프론트의 Access Token과 사용자 상태를 지운다. 다른 기기의 세션은 유지한다.

## 실패 계약

지원하는 제공사 Callback 실패는 설정된 실패 URL에 `?code=...`를 붙인 303 응답이다. 본문은 비어 있고 Cache-Control=no-store, Referrer-Policy=no-referrer를 설정한다. 제공사 오류 원문은 전달하지 않는다.

| code | 의미 |
|---|---|
| AUTH_401_1 | 제공사 사용자 정보 형식 오류 |
| AUTH_401_2 | 이메일 미제공 |
| AUTH_401_3 | 유효하거나 인증된 이메일이 아님 |
| AUTH_401_6 | 취소·state 오류·제공사 인증 실패 등 |
| AUTH_503_1 | 몽글 로그인 처리 장애 |

OAuth가 활성화된 환경의 Google/미지원 제공사 로그인 시작·Callback은 외부로 이동하지 않고 400 공통 JSON `AUTH_400_1`을 반환한다. 다른 제공사의 등록이 외부 설정으로 주어져도 카카오만 통과시킨다. 프론트 실패 화면은 허용한 코드만 메시지로 매핑한다. 토큰 또는 서버/제공사 오류 설명을 URL에서 기대하지 않는다.

## HTTPS·쿠키 확인

Refresh/CSRF 쿠키는 API 호스트 전용이며 Path=/api/v1/auth다. 프론트에서 직접 읽거나 Domain=.mongle.art로 바꿀 필요가 없다. `www.mongle.art` 프론트와 `api.mongle.art` API는 Origin이 다르므로 CORS와 credentials가 필요하다. 실제 프론트 주소가 Vercel 도메인이면 쿠키의 사이트 조건도 달라지므로 이 배포와 같은 것으로 가정하지 않는다.

Nginx는 실제 HTTPS/Host를 전달하고 `SERVER_FORWARD_HEADERS_STRATEGY=framework`를 적용한다. 앱 8080은 루프백으로 제한한다. 로컬 HTTP 테스트는 `local,oauth`, OAUTH_COOKIE_SECURE=false, localhost 프론트 Origin/완료/실패 URL을 명시한다. 운영 mysql/prod에서는 HTTP 복귀와 Secure=false 세션 쿠키를 허용하지 않는다.

## 검증 범위

모의 종단 테스트는 실제 Spring 보안 필터와 루프백 카카오 모의 서버를 사용한다. 로그인·state·쿠키·CSRF·Refresh·로그아웃·중복 가입 방지·잘못된 이메일/응답·취소·재사용·장애·CORS를 검증한다. Google/미지원 경로 차단과 이전 Google 설정으로 등록이 되살아나지 않는 것도 검증한다. 네이티브 MySQL8.4 테스트는 기존 VARCHAR 스키마의 카카오 계정 저장/조회 및 사전 점검 SQL의 비지원 제공자 탐지를 검증한다.

실제 카카오 계정 동의, 카카오 콘솔 키/Redirect URI, EC2 환경변수, RDS 저장, Nginx/HTTPS, 프론트 화면 복귀는 실환경에서 별도 확인해야 한다. 회원탈퇴/카카오 연결 해제는 이 기능에 포함하지 않는다.

참고: [카카오 REST 로그인](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [Spring Security OAuth 로그인 설정](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html).
