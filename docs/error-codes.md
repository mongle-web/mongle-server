# API 에러 코드

외부 응답의 `code`는 `도메인_HTTP상태_순번` 형식을 사용한다. 도메인과 HTTP 상태가 같으면
순번은 1부터 증가하며, 이미 공개한 코드는 다른 오류에 재사용하거나 재정렬하지 않는다.
HTTP 상태와 메시지, 예외 처리 흐름은 코드 형식 변경과 무관하게 유지한다.

내부 생성 상태의 `failureCode`(`CALL_FAILED`, `SOURCE_CHANGED` 등)는 API 에러 응답 코드와
용도가 다르므로 이 규칙의 대상이 아니다.

## 공통·인증·사용자

| 구분 | 오류 | 코드 | HTTP |
| --- | --- | --- | ---: |
| 공통 | BAD_REQUEST | `COMMON_400_1` | 400 |
| 공통 | VALIDATION_FAILED | `COMMON_400_2` | 400 |
| 공통 | REQUEST_FAILED | `COMMON_400_3` | 400 |
| 공통 | UNAUTHORIZED | `COMMON_401_1` | 401 |
| 공통 | FORBIDDEN | `COMMON_403_1` | 403 |
| 공통 | NOT_FOUND | `COMMON_404_1` | 404 |
| 공통 | METHOD_NOT_ALLOWED | `COMMON_405_1` | 405 |
| 공통 | NOT_ACCEPTABLE | `COMMON_406_1` | 406 |
| 공통 | CONFLICT | `COMMON_409_1` | 409 |
| 공통 | PAYLOAD_TOO_LARGE | `COMMON_413_1` | 413 |
| 공통 | UNSUPPORTED_MEDIA_TYPE | `COMMON_415_1` | 415 |
| 공통 | TOO_MANY_REQUESTS | `COMMON_429_1` | 429 |
| 공통 | INTERNAL_SERVER_ERROR | `COMMON_500_1` | 500 |
| 공통 | SERVICE_UNAVAILABLE | `COMMON_503_1` | 503 |
| 공통 | GATEWAY_TIMEOUT | `COMMON_504_1` | 504 |
| 인증 | UNSUPPORTED_PROVIDER | `AUTH_400_1` | 400 |
| 인증 | INVALID_PROVIDER_RESPONSE | `AUTH_401_1` | 401 |
| 인증 | EMAIL_REQUIRED | `AUTH_401_2` | 401 |
| 인증 | EMAIL_NOT_VERIFIED | `AUTH_401_3` | 401 |
| 인증 | INVALID_TOKEN | `AUTH_401_4` | 401 |
| 인증 | INVALID_REFRESH_TOKEN | `AUTH_401_5` | 401 |
| 인증 | LOGIN_FAILED | `AUTH_401_6` | 401 |
| 인증 | LOGIN_UNAVAILABLE | `AUTH_503_1` | 503 |
| 사용자 | INVALID_NICKNAME | `USER_400_1` | 400 |
| 사용자 | ONBOARDING_REQUIRED | `USER_403_1` | 403 |
| 사용자 | USER_NOT_FOUND | `USER_404_1` | 404 |
| 사용자 | ONBOARDING_ALREADY_COMPLETED | `USER_409_1` | 409 |

## 꿈·분석·서사·이미지

| 구분 | 오류 | 코드 | HTTP |
| --- | --- | --- | ---: |
| 꿈 | DATE_REQUIRED | `DREAM_400_1` | 400 |
| 꿈 | FUTURE_DATE | `DREAM_400_2` | 400 |
| 꿈 | INVALID_TEXT | `DREAM_400_3` | 400 |
| 꿈 | INVALID_EMOTIONS | `DREAM_400_4` | 400 |
| 꿈 | EMOTIONS_IMMUTABLE | `DREAM_400_5` | 400 |
| 꿈 | INVALID_TITLE | `DREAM_400_6` | 400 |
| 꿈 | EMPTY_UPDATE | `DREAM_400_7` | 400 |
| 꿈 | NOT_FOUND | `DREAM_404_1` | 404 |
| 꿈 | DATE_OCCUPIED | `DREAM_409_1` | 409 |
| 꿈 | INVALID_STATE | `DREAM_409_2` | 409 |
| 꿈 | VERSION_CONFLICT | `DREAM_409_3` | 409 |
| 분석 | NOT_FOUND | `ANALYSIS_404_1` | 404 |
| 분석 | INVALID_OUTPUT | `ANALYSIS_502_1` | 502 |
| 분석 | CALL_FAILED | `ANALYSIS_502_2` | 502 |
| 분석 | UNAVAILABLE | `ANALYSIS_503_1` | 503 |
| 서사 | NOT_FOUND | `STORY_404_1` | 404 |
| 서사 | ANALYSIS_REQUIRED | `STORY_409_1` | 409 |
| 서사 | ANALYSIS_STALE | `STORY_409_2` | 409 |
| 서사 | VERSION_CONFLICT | `STORY_409_3` | 409 |
| 서사 | INVALID_OUTPUT | `STORY_502_1` | 502 |
| 서사 | CALL_FAILED | `STORY_502_2` | 502 |
| 서사 | UNAVAILABLE | `STORY_503_1` | 503 |
| 이미지 | INVALID_OPTION | `IMAGE_400_1` | 400 |
| 이미지 | NOT_FOUND | `IMAGE_404_1` | 404 |
| 이미지 | STORY_REQUIRED | `IMAGE_409_1` | 409 |
| 이미지 | VERSION_CONFLICT | `IMAGE_409_2` | 409 |
| 이미지 | REGENERATION_REQUIRED | `IMAGE_409_3` | 409 |
| 이미지 | INVALID_OUTPUT | `IMAGE_502_1` | 502 |
| 이미지 | CALL_FAILED | `IMAGE_502_2` | 502 |
| 이미지 | UNAVAILABLE | `IMAGE_503_1` | 503 |

## AI Gateway

Gateway 코드는 이미 공개된 번호를 그대로 유지한다.

| 오류 | 코드 | HTTP |
| --- | --- | ---: |
| INVALID_REQUEST | `AI_502_1` | 502 |
| AUTHENTICATION_FAILED | `AI_502_2` | 502 |
| INVALID_RESPONSE | `AI_502_3` | 502 |
| INCOMPLETE_RESPONSE | `AI_502_4` | 502 |
| INSUFFICIENT_CREDIT | `AI_503_1` | 503 |
| RATE_LIMITED | `AI_503_2` | 503 |
| PROVIDER_UNAVAILABLE | `AI_503_3` | 503 |
| TIMEOUT | `AI_504_1` | 504 |
