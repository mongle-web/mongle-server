# LINER 구조화 출력 스키마 호환성 (#115)

## 확인한 증거와 수정 범위

2026-10-31 인프라 검증 인계에 따르면 운영 배포 `d284a00b64959d4eae74bad5084b02a3467108b4`에서
분석 성공 뒤 `DREAM_NARRATIVE`가 `liner-mark-1.1`의 HTTP 400 `invalid_request`로 실패했다.
같은 배포 JAR의 프롬프트·가상 입력·모델로 직접 비교한 결과는 다음과 같다.

| 운영 비교 요청 | 인계받은 결과 |
|---|---|
| 기존 story-schema.json | HTTP 400 / invalid_request |
| `$schema`, 길이·개수·범위·패턴·중복·조건부 제약을 제거한 서사 스키마 | HTTP 200 / finish_reason=stop |

이 비교는 제약 묶음을 제거했을 때의 결과다. 개별 키워드를 따로 비교하지 않았으므로 특정 키워드가
항상 미지원이라고 단정하지 않는다. 세계관 연결·요약·종합 해석의 실제 LINER 성공도 이 비교로 증명된 것은 아니다.
코드 검증에서는 외부 LINER에 유료 요청을 보내지 않고 로컬 HTTP 서버로 실제 전송 본문을 관찰한다.

#106에서 이미 단순화한 `structure-schema.json`은 변경하지 않는다. 다음 네 리소스에서 업무 제약만 제거한다.
공통 `LinerPayloadMapper`에 자동 삭제 규칙을 넣지 않으며 다른 스키마를 조용히 변형하지 않는다.

| 리소스 | 전송 스키마 조정 | 유지하는 Java 업무 검증 |
|---|---|---|
| story-schema.json | `$schema`, min/maxLength·min/maxItems·minimum/maximum·allOf/if/then/else 제거 | StoryValidator: 입력 장면 1~10개, 모든 장면 순서대로 1회, 전체 문단 1~19개 및 장면 수 기반 상한, 연속 sequence, SCENE 정수 매핑, AI_BRIDGE의 null 및 두 SCENE 사이 최대 1개, 문단 1~1500·합계 6000 코드포인트, 원문 응답 32000 UTF-16 단위 상한 |
| world-bridge-schema.json | minLength/maxLength 제거 | BridgeValidator: content만, 공백/보이지 않는 문자만인 결과 거절, 최대 500 코드포인트, 제어 문자·중복 키·추가 JSON 거절, 응답 8000 단위 상한 |
| world-summary-schema.json | minLength/maxLength 제거 | WorldSummaryValidator: generatedTitle만, 비어 있지 않은 한 줄 최대 80 코드포인트, 제어/포맷/금지 문자·중복 키·추가 JSON 거절, 응답 2000 단위 상한 |
| recent-interpretation-schema.json | minLength/maxLength 제거 | InterpretationValidator: interpretation만, 공백뿐인 결과 거절, 최대 2000 코드포인트, 제어 문자·중복 키·추가 JSON 거절, 응답 16000 단위 상한 |

모든 스키마의 필드명·타입·required·additionalProperties=false와 서사의 SCENE/AI_BRIDGE enum을 유지한다.
서사의 `sceneSequence` 구조 타입은 integer/null을 유지하고 **SCENE이면 해당 입력 장면 번호, AI_BRIDGE이면 null**을
Java에서 검사한다. `strict=true`, 모델·키·외부 통신·오류 처리 정책도 유지한다.

## 프롬프트·캐시·성공 결과

기존 시스템 프롬프트에 장면 매핑·브리지 위치·순서·문단 수·본문/합계 길이와 각 기능의 출력 필드·길이가 이미 명시돼 있다.
업무 의미와 시스템 프롬프트를 바꾸지 않으므로 이번 수정은 기존 버전을 유지한다.

- `story-v2`
- `world-bridge-v1`
- `world-summary-v1`
- `recent-interpretation-v1`

특히 BridgePrompt.VERSION은 연결 결과 재사용과 실패 재시도 키에 참여한다. WorldSummary/Interpretation 버전은
대상 지문·갱신 판단에 참여한다. 전송 제약만 바꾸면서 버전까지 올려 기존 성공 결과의 재사용이나 실패 재시도 키를
바꾸지 않는다. 수정된 리소스는 Git 커밋·JAR·배포 이력으로 구분한다. 이후 생성 의미/시스템 프롬프트가 달라지는 변경은
버전 변경과 기존 실패 재시도·캐시 호환성을 함께 검토한다.

기존 성공 분석·서사·세계관·종합 해석을 삭제하거나 자동 재생성하지 않는다. 실패/진행 중 이전 성공 결과 보존,
멱등성·동시성·새 결과 확정 정책도 기존 서비스를 그대로 사용한다.

## 검증

- `LinerDomainSchemaIntegrationTest`: 실제 5개 Generator → DreamAiGateway → LinerAiGateway → LinerClient →
  로컬 HTTP 서버에서 model/messages/response_format/json_schema/strict와 구조·required·enum을 검증한다.
  수정 전에는 서사·연결·요약·해석 4개 경우가 제약 존재로 실패하고 수정 후 통과한다. 분석 스키마도 함께 확인한다.
- 같은 테스트에서 HTTP 200/stop으로 반환된 문단 순서·조건부 매핑·enum·공백·길이 위반을 Java가 거절함을 확인한다.
- `StoryValidatorBoundaryTest`: null/정수 매핑·연속 브리지 거절, 10장면/19문단 경계, 누락·과다 문단,
  Unicode 1500/6000 경계·초과, 정수 범위 밖 값과 원문 없는 안전한 오류를 확인한다.
- 기존 Bridge/WorldSummary/Interpretation Validator 및 서비스 회귀 테스트로 길이·타입·추가 필드·중복·기존 결과 보존을 검증한다.
- `DreamGenerationIntegrationTest`: 최초 ANALYSIS 성공 → 서사 INVALID_REQUEST 실패 → 실제 HTTP `/generation/retry` →
  STORY COMPLETED를 검증한다. 성공 분석 내용/ID가 유지되고 분석 호출은 1회, 서사 호출은 재시도 포함 2회다.
  완료 뒤 retry 재요청도 AI 호출을 추가하지 않는다.

로컬 HTTP 모의 응답 성공은 실서비스 모델의 호환성·품질·비용 검증을 대체하지 않는다.

## 배포 후 인프라·기능 검증

DB·Flyway·초기 스키마·환경변수·키·모델 변경은 없다. 운영 파일 수정, 수동 DB 상태 변경, 기존 baseline 재등록은 필요하지 않다.
PR 머지 및 기존 CI/CD의 새 커밋 배포 성공과 health UP을 확인한 후 아래를 진행한다.

1. 실패한 기존 꿈을 삭제하거나 새로 만들지 않는다. 해당 소유자 계정으로 인증한다.
2. `GET /api/v1/dreams/{dreamId}`에서 **최신 revision**을 얻는다. sourceRevision이나 generationVersion과 혼동하지 않는다.
3. `GET /api/v1/dreams/{dreamId}/generation`에서 `stage=STORY`, `status=FAILED`와 분석/서사 상태를 확인한다.
4. 아래 JSON으로 재시도한다. 기존 분석 전체 재생성을 의미하는 `/generation/regenerate`를 사용하지 않는다.

```http
POST /api/v1/dreams/{dreamId}/generation/retry
Authorization: Bearer {accessToken}
Content-Type: application/json

{"revision":123}
```

`123`은 예시다. 직전 GET의 revision을 사용하며 409이면 최신 상태·revision을 다시 확인한다.
정상 접수는 HTTP 202다. 기존 워커 설정에서 실행되므로 무한 자동 재요청하지 않고 GET으로 상태를 확인한다.

5. GET generation을 2~3초 간격으로 확인해 최종 `STORY / COMPLETED`를 확인한다. 시간 초과·실패 시 failureCode를 확인한다.
6. 분석 ID/결과와 로그의 taskType을 대조해 **DREAM_STRUCTURE를 불필요하게 다시 호출하지 않았는지** 확인한다.
   재시도 구간의 `DREAM_NARRATIVE` HTTP 상태·모델·finish_reason·안전한 오류 코드로 판단한다.
7. 성공 서사 확인 후 꿈 이미지 생성, 꿈 2개 이상의 세계관 연결, 세계관 요약, 최근 꿈 종합 해석을 각각 실환경에서 검증한다.
   이미지에는 기존 크레딧 정책을 적용하며 임의 무료 처리나 운영 플래그 변경을 하지 않는다.

확인 결과에는 배포 SHA·요청 시각·X-Request-Id·단계/상태·HTTP 상태·failureCode만 공유한다.
꿈 원문·AI 출력 본문·Access Token·키·운영 환경 파일·외부 오류 본문은 로그/공유 자료에 넣지 않는다.
