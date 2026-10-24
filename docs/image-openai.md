# OpenAI 비동기 이미지 Provider (#56)

## 범위와 기본 상태

미병합 #55의 `feat/54-s3-image-storage`를 기반으로 `OpenAiImageGenerator`를 추가한다.
실제 모델·크기·품질·화면의 스타일/분위기는 **사용자 확인상 미정**이다. 운영 기본값을 임의로 채우지 않는다.
`MONGLE_IMAGE_OPENAI_ENABLED=false`가 기본이며 이 상태에서는 unavailable 생성기를 사용한다.
Provider 비활성 시 OpenAI HTTP 클라이언트·응답 실행기·타이머를 만들지 않는다.
기존 테스트/사용자 정의 ImageGenerator가 있으면 기본 어댑터 생성을 생략한다.
활성화한 어댑터의 available은 준비된 설정/열린 자원을 뜻하며 계정·모델 권한·쿼터·실제 호출 성공을 보장하지 않는다.

운영 어댑터는 `https://api.openai.com/v1/images/generations`에 요청한다. endpoint 설정을 외부에 노출하지 않고 리다이렉트를 따라가지 않는다.
테스트 생성자는 가짜 인증 정보/로컬 HTTP를 주입한다. 이번 검증에서 실제 OpenAI/AWS API를 호출하지 않는다.
서비스 크레딧 차감·환급, 이미지 API 비용 DB 원장·결제/광고 보상은 별도 범위다.

## 요청과 결과

서버가 검증한 서사 sections, 허용된 style/mood 키와 설정의 프롬프트 매핑을 사용한다.
`image-prompt.txt` 지침에 JSON 데이터를 붙이며 SCENE과 AI_BRIDGE를 구분한다.
원문·프로필·인증 토큰·감정 기록을 별도로 가져와 넣지 않는다. 서사 안의 지시문은 입력 데이터로 취급하도록 지침을 제공한다.
스타일/분위기 설명은 운영 매핑에서 공급하며 사용자 자유 입력을 새 시스템 지시로 사용하지 않는다.

요청은 `n=1`, `stream=false`, 명시적 model/size/quality/output_format이다.
GPT Image의 `data[0].b64_json`을 사용하고 레거시 response_format/style 파라미터나 외부 이미지 URL 다운로드를 사용하지 않는다.
JSON은 이미지 10MiB의 최대 base64 길이 + 64KiB 메타데이터까지만 수신한다.
Content-Length가 크면 즉시 거절하고, 없거나 청크 전송이어도 수신 도중 크기를 검사해 초과 구독/통신을 취소한다.
data가 정확히 1개인지, 문자열 base64와 디코딩 크기/출력 메타데이터를 검사한다.
실제 PNG/JPEG 헤더·디코딩·픽셀/크기는 기존 ImagePayload.parse와 완료 풀에서 검증한다. 응답 MIME만 신뢰하지 않는다.
WebP는 이 포트가 지원하지 않는다. 새 DB 스키마/수동 SQL 변경은 없고 선행 PR SQL은 필요하다.

## 비동기 자원과 실행 예산

JDK HttpClient.sendAsync로 전체 본문 수신 Future를 연결한다. 네트워크 응답을 기다리는 get/join이나 대기용 풀 작업은 없다.
JSON/base64 처리는 `openai-image-response-*`의 제한된 풀/큐에서 실행한다. DB/파일 IO는 이 풀에 넣지 않는다.
Provider 호출 수는 이미지 max-concurrent-calls, 응답 큐는 같은 수, 응답 스레드는 response-threads와 호출 상한 중 작은 수다.
한 개 타이머 스레드가 본문 수신·응답 큐 대기를 포함한 total-timeout을 검사하고 초과 통신을 취소한다.
어댑터 Future 취소와 HTTP 응답 취소는 내부 완료/파일 정리/슬롯 반환을 취소하지 않는다. Provider 종료/시간 초과는 실제 HTTP Future를 취소한다.
종료 중에는 새 요청을 보내지 않고 진행 Future를 안전한 실패로 완료한다.
Spring 의존 순서로 Provider 통신 종료 → 이미지 완료 풀 종료 → 저장소 종료를 유지한다. 정리 워커도 저장소보다 먼저 종료된다.

이미지 시도 lease는 기존 2분, MVC HTTP 제한은 기존 130초다. 이를 임의로 늘리지 않는다.
활성화 시 다음 **설정된 외부 호출 예산**이 2분보다 작은지 검사한다.

`Provider total + ceil(진행 이미지 상한 / 결과 처리 스레드 수) × (S3 total × 2) + 5초`

×2는 각 결과의 업로드와 필요한 보상 삭제, 5초는 완료 처리 여유다.
이 검사는 DB/CPU/OS 지연을 시간 제한으로 강제하거나 실제 완료를 보장하지 않는다. 늦은 시도는 기존 완료 시점 검사로 거절/보상한다.
가짜/다른 ImageAssetStore를 쓸 때도 해당 저장소가 설정된 S3 total 이하의 IO 제한을 지키도록 공급해야 한다.
기존 기본 진행 상한16/결과스레드2와 S3 total10초는 Provider 활성화 예산에 맞지 않는다.
예를 들어 호출 상한4/결과스레드2·Provider60초·S3 total10초는 `60+2×20+5=105초`로 검사에 맞는다.
이 수치는 동작 가능한 구성 예시이며 운영 모델/부하의 확정 추천값은 아니다.
공식 가이드는 복잡한 생성이 2분까지 걸릴 수 있다고 설명한다. 실제 품질/지연/실패율 측정 뒤 모델/옵션/실행 예산 또는 별도 lease 정책을 결정한다.

## 재사용·실패·유료 호출

어댑터 수준 자동 재시도는 없다. 인증 실패·요청 제한·제공자 오류·연결 종료·타임아웃 뒤 POST를 자동 재전송하지 않는다.
타임아웃/취소로 실제 제공자 작업과 외부 요금 발생까지 취소됐다고 보장하지 않는다.
서버 재시작 뒤 유료 요청 자동 재호출이나 영속 접수 대기열을 추가하지 않는다.
기존 결과 재사용은 무료 재사용 경로를 유지하고 명시적 regenerate는 새 Provider 호출이다. 설정 변경만으로 기존 성공 결과를 갱신하지 않는다.

| 결과/상태 | 저장되는 failureCode |
| --- | --- |
| 401/403 | PROVIDER_AUTH_FAILED |
| 429 | PROVIDER_RATE_LIMITED |
| 5xx/통신·종료/실행 자원 거절 | PROVIDER_UNAVAILABLE |
| 본문/처리 제한 시간 초과 | PROVIDER_TIMEOUT |
| 기타 비성공 HTTP(리다이렉트 포함) | PROVIDER_REJECTED |
| 잘못된 JSON/base64/크기/실제 이미지 | INVALID_OUTPUT |

예약 후 생성 실패는 기존 200/status=FAILED 계약이며 이전 성공 파일을 유지한다. 설정 미연결/예약 전 오류의 503 계약은 그대로다.
이 코드는 계정 잔액 부족과 일시적 요청 제한을 오류 본문으로 세분화하지 않는다. 둘 다 재시도 없이 안전하게 표시한다.
삭제된 결과는 늦은 성공/실패에서도 404다. 미채택 업로드 보상 실패는 영속 정리 요청을 남기고 기존 S3 워커가 처리한다.

Provider 완료 로그에는 imageId/안전한 attemptId·requestId/분류/HTTP 상태/경과 시간만 남긴다.
BYTES_RECEIVED는 base64 바이트 수신 완료이며 실제 이미지 검증/DB 성공을 뜻하지 않는다. 최종 상태는 이미지 API로 확인한다.
서사/프롬프트/이미지/base64·인증 값·외부 오류 본문은 로그/예외 체인에 넣지 않는다. SDK/JDK HTTP wire/header 로그도 운영에서 켜지 않는다.
사용량/금액을 추정해 텍스트 AI 로그나 크레딧 원장에 넣지 않는다.

## 운영 설정

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| MONGLE_IMAGE_OPENAI_ENABLED | false | Provider 활성화 |
| OPENAI_API_KEY | 빈 값 | 환경에서 주입하는 인증 값 |
| MONGLE_IMAGE_OPENAI_MODEL | 빈 값 | 명시적으로 선택할 지원 모델 |
| MONGLE_IMAGE_OPENAI_SIZE | 빈 값 | 1024x1024 / 1536x1024 / 1024x1536 |
| MONGLE_IMAGE_OPENAI_QUALITY | 빈 값 | 선택 모델이 지원하는 품질 |
| MONGLE_IMAGE_OPENAI_OUTPUT_FORMAT | png | png/jpeg 바이트 형식 |
| MONGLE_IMAGE_OPENAI_CONNECT_TIMEOUT | 3s | 연결 제한, 100ms~10s |
| MONGLE_IMAGE_OPENAI_TOTAL_TIMEOUT | 60s | 전체 제한, connect보다 길고 90초 이하 |
| MONGLE_IMAGE_OPENAI_RESPONSE_THREADS | 2 | JSON/base64 응답 처리, 1~8 |

지원 모델 allowlist는 확인한 GPT Image의 1-mini/1.5/2/2.5 계열과 해당 명시적 스냅샷, chatgpt-image-latest다.
퇴역 모델은 거절하며 새 모델은 문서/호환성 검증 후 allowlist에 추가한다.
기본 품질 허용값 low/medium/high/auto, 2.5 계열은 xhigh/max도 허용한다. 호환되는 공통 크기 3종만 이번 범위에서 지원한다.
스타일과 분위기는 기존 `mongle.image.styles/moods` 목록 및 `mongle.image.openai.style-prompts/mood-prompts` 맵을 운영 YAML 등에 함께 공급한다.
styles는 비어 있으면 안 되고 두 목록/매핑 키 집합이 정확히 일치해야 한다. moods는 양쪽 모두 비울 수 있다.
선택지와 설명을 이슈나 코드에서 임의로 확정하지 않는다. API 키/비밀번호는 채팅·이슈·저장소에 넣지 않는다.
S3 및 정리 워커 활성화 값은 각각 별개로 기본 false다. 실제 계정/버킷/도메인/인프라 준비와 실환경 검증은 별도다.

## 검증

로컬 HTTP/가짜 키·S3 SDK 모의 전송·H2로 요청/프롬프트·제한된 비동기 대기·오류·본문 제한·JPEG·취소/종료를 확인한다.
통합 회귀는 중복/성공 재사용·명시적 새 생성·이전 성공 보존·원본 생성 보호·삭제 뒤 늦은 업로드/보상 실패/워커 완료를 확인한다.
실제 OpenAI 유료 호출·AWS 접속·운영 DB/SQL/권한 변경은 실행하지 않는다. 실품질/비용/권한/쿼터 검증을 마친 상태로 표현하지 않는다.

## 공식 참고 (2026-10-24 확인)

- [OpenAI Images API](https://developers.openai.com/api/reference/resources/images/methods/generate)
- [OpenAI 이미지 생성 가이드](https://developers.openai.com/api/docs/guides/image-generation)
