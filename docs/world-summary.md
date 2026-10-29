# 세계관 AI 제목·공통 키워드·반복 감정

## 제공 범위

폴더 이름 `name`은 그대로 두고 완성 세계관의 `generatedTitle`을 별도로 제공한다. 선택 목록이 아니라 **공개된 완성본**의 고정 서사 버전과 연결 문장으로 LINER가 제목을 생성한다. 꿈 원문이나 현재 분석으로 스냅샷을 몰래 교체하지 않는다. 이미지·OpenAI·S3·크레딧과 독립적인 무료 텍스트 기능이다.

- 공통 키워드: 고정 서사 버전의 `analysis_json.displayKeywords`에서 정규화한 같은 키워드가 서로 다른 꿈 2개 이상에 등장할 때만 반환한다. 꿈별 중복을 제거하고, 꿈 수 내림차순·정규화 키 오름차순으로 최대 5개를 반환한다. 표시 문구도 해당 분석에 실제 존재하는 값이다.
- 반복 감정: 같은 버전에 기록된 `source_emotions`의 기존 7종 감정 중 서로 다른 꿈 2개 이상에 등장한 것만 반환한다. 꿈 수 내림차순·감정 코드 오름차순이다. 문장에서 감정을 새로 추측하지 않는다.
- `dreamCount`: 해당 키워드/감정을 포함한 서로 다른 출처 꿈 수다. 단어 등장 횟수가 아니다. 근거가 없으면 빈 배열이며 보충하지 않는다.
- `limitedEvidence`: 출처 꿈이 2개 미만이거나 일부 구버전의 분석·감정 스냅샷이 없는 경우다. 구버전 null 값은 현재 분석/감정으로 채우지 않는다.
- 세계관 생성·부분 반영 성공 시 무료 결과 생성 작업을 같은 트랜잭션에 접수한다. AI 호출은 그 트랜잭션 밖의 별도 워커가 실행한다. 제목 생성 실패 때문에 완성 세계관·연결 서사가 실패하지 않는다.

## 프론트 연동

기본 주소 `https://api.mongle.art`, 인증은 온보딩을 완료한 회원의 Bearer 토큰이다. 두 API의 요청 본문은 없다. 응답은 기존 `ApiResponse.data`에 담긴다.

| 메서드 | 경로 | 처리 |
|---|---|---|
| GET | `/api/v1/worlds/{worldId}/summary` | AI 호출 없이 결과·상태·변경 안내 조회, HTTP 200 |
| POST | `/api/v1/worlds/{worldId}/summary` | 현재 완성본으로 최초 생성·실패 재시도. 대기/진행은 202, 완료 재사용·EMPTY는 200 |

신규 완성본은 자동 접수되므로 GET으로 상태를 확인한다. 배포 전 만들어진 기존 완성본이 `NOT_GENERATED`이면 POST로 생성한다. `PENDING/PROCESSING`은 화면에서 상태를 조회하고 무한 POST 재전송은 하지 않는다. 모든 응답은 `Cache-Control: no-store`다.

```json
{
  "worldId": 12,
  "compositionRevision": 2,
  "resultCompositionRevision": 2,
  "generationVersion": 3,
  "status": "COMPLETED",
  "failureCode": null,
  "generatedTitle": "하늘을 건너 만난 바다",
  "commonKeywords": [{"keyword": "바다", "dreamCount": 2}],
  "recurringEmotions": [{"code": "HAPPY", "label": "행복", "dreamCount": 2}],
  "sourceCount": 2,
  "limitedEvidence": false,
  "sourceChanged": false,
  "refreshNeeded": false,
  "hasPreviousResult": false,
  "generatedAt": "2026-10-29T10:00:00Z",
  "promptVersion": "world-summary-v1"
}
```

`generatedTitle`은 없으면 null이며 두 목록은 빈 배열이다. `sourceCount`는 현재 폴더 선택 수가 아니라 **표시 중인 성공 결과의 출처 수**다. 성공 전에는 0이다. `compositionRevision`은 현재 완성본, `resultCompositionRevision`은 표시 중인 결과의 버전이다. `generationVersion`은 상태 저장 버전이며 POST에 보낼 필요가 없다.

| status | 화면 처리 |
|---|---|
| NOT_GENERATED | 폴더만 만들었거나 생성 요청 전. 완성본이 있으면 POST 가능 |
| EMPTY | 꿈 삭제로 완성본 출처가 0개. 제목/패턴을 표시하지 않으며 AI를 호출하지 않음 |
| PENDING | 작업 접수·대기. GET으로 확인 |
| PROCESSING | AI 생성 중. 이전 성공 결과가 있으면 함께 표시 가능 |
| COMPLETED | 결과 표시. 변경 안내 여부도 확인 |
| FAILED | 오류 안내와 명시적 POST 재시도. 유효한 이전 성공 결과는 보존 |

`sourceChanged=true`는 현재 원문/감정과 완성본 스냅샷이 다름을 뜻한다. `refreshNeeded=true`는 원문 변경·완성본 교체·설정 변경 또는 미생성 상태의 안내다. **원문만 수정된 경우 먼저 분석/서사 재생성 후 세계관에 부분 반영하거나 완성본을 다시 구성한다.** 같은 완성본으로 POST만 반복하면 기존 결과를 재사용한다. 폴더 이름·선택 목록만 편집한 경우 성공 결과를 바꾸지 않는다.

생성 중·실패·갱신 필요 상태에서 유효한 이전 결과가 있으면 `hasPreviousResult=true`다. 삭제된 꿈을 사용한 제목·키워드·감정은 제거한다. A+B 세계관에서 B를 삭제해 A만 남으면 기존 텍스트 결과를 비우고 명시적 POST로 단일 출처 제목을 만들 수 있다. 이때 두 반복 목록은 빈 배열이다. **기존 세계관 이미지 보존 및 이미지 신규 생성/재생성 최소 꿈 2개 정책은 변경하지 않는다.**

## 실패·중복·삭제

- 회원 단위 잠금으로 꿈/세계관 변경·작업 접수·결과 저장을 직렬화하고, HTTP 응답 대기에는 DB 잠금이나 트랜잭션을 유지하지 않는다.
- 동일 완성본·프롬프트·생성 설정의 성공 결과와 같은 버전의 진행 요청은 재사용한다. 세계관을 다시 완성해 구성 버전만 바뀌어도 고정 서사·연결부·출처 버전·설정이 동일하면 기존 결과를 재사용하고 결과의 구성 버전만 갱신한다. 진행 중 설정이 바뀌면 결과 저장 시 거절한다.
- 작업 lease는 2분이다. 만료는 `FAILED/ATTEMPT_EXPIRED`로 표시하며 자동 AI 재전송은 하지 않는다. POST로 새 시도를 시작한다. 만료된 시도의 늦은 응답은 새 결과를 덮어쓸 수 없다.
- 서사 버전 ID·출처 버전·관찰한 현재 원문 버전·구성 순서·연결 ID를 저장하고 완료 시 다시 확인한다. 호출 도중 원문 수정, 출처 삭제, 세계관 교체, 폴더 삭제 시 오래된 응답을 버린다.
- 이전 결과와 새 시도의 출처를 별도 저장한다. 이전 결과만 사용한 꿈이 삭제되면 그 결과만 제거하고, 별개 출처의 유효한 새 시도는 계속할 수 있다.
- 실패 코드: `GENERATION_FAILED`, `INVALID_OUTPUT`, `UNAVAILABLE`, `INPUT_CHANGED`, `INPUT_UNAVAILABLE`, `INPUT_TOO_LARGE`, `SOURCE_DELETED`, `ATTEMPT_EXPIRED`. 외부 응답·키·꿈 원문을 오류에 노출하지 않는다.
- HTTP 오류: 인증 401, 온보딩 필요 403, 없는/타인 폴더 404, `WORLD_SUMMARY_409_1` 완성본 필요, `_409_2` 세계관 구성 작업 중, `_409_3` 출처 확인 불가, `_400_1` 입력 한도 초과, `_503_1` 기능 비활성/Provider 미설정.

전체 입력은 DB에서 본문 길이를 먼저 합산해 제한을 확인하고, 직렬화한 AI 입력도 다시 검사한다. 기본 120,000자이며 초과 시 일부 꿈을 조용히 생략하지 않는다. 폴더 선택 수 자체를 제한하는 정책이 아니다. 외부 호출은 기존 LINER Gateway·동시 처리 제한·결과 저장 풀을 공유한다. 모델에는 고정 서사·연결부·집계 근거만 전달하고 회원/꿈/세계관 ID·인증값은 넣지 않는다.

## 배포·활성화

새 Flyway `V5__world_summaries.sql`이 `world_summaries`, `world_summary_sources` 두 테이블을 추가한다. V1·V3·V4와 기존 Flyway 이력은 바꾸지 않는다. 기존 CD의 마이그레이션 단계로 V5가 한 번 적용된 뒤 새 JAR가 배포되게 한다. 기존 업무 테이블을 재초기화하거나 baseline을 재등록하지 않는다. 운영 앱 계정은 DML 전용을 유지한다.

기능은 기본 비활성이다. **#95 머지/CD 성공 후**, 인프라 채팅에서 기존 환경 파일에 중복 없이 설정하고 systemd를 재시작한다.

```ini
MONGLE_WORLD_SUMMARY_ENABLED=true
MONGLE_WORLD_SUMMARY_WORKER_ENABLED=true
```

기존 `LINER_API_KEY`·모델 설정과 LINER 네트워크 연결이 필요하다. OpenAI/S3/PayApp이나 추가 키는 필요 없다. 환경 파일·DB·운영 플래그는 이 코드 작업에서 직접 변경하지 않는다.

선택 설정은 `MONGLE_WORLD_SUMMARY_POLL_INTERVAL=2s`, `MONGLE_WORLD_SUMMARY_BATCH_SIZE=20`, `MONGLE_WORLD_SUMMARY_MAX_CONCURRENT_CALLS=2`, `MONGLE_WORLD_SUMMARY_MAX_INPUT_CHARACTERS=120000`이다. 입력 한도 범위는 1,000~120,000자다.

활성화 후 health 확인 → 성공 서사 2개로 세계관 구성 → summary GET의 PENDING/PROCESSING→COMPLETED 확인 → 같은 POST 재사용과 크레딧 무차감 확인 순서로 검증한다. 기존 완성본은 POST로 접수한다. 실제 LINER 제목의 품질·운영 활성화·브라우저 전체 흐름은 별도 검증이며, 코드 테스트는 Fake Provider를 사용한다.
