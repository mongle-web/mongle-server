# 꿈 저장 완료 후 자동 분석·서사화

## 범위와 저장 흐름

#35는 #33의 비동기 분석·서사화를 자동으로 연결한다. 분석 생성기·요소 추출·공통 Gateway를 새로 만들지 않는다.
상준의 Archive(#34)는 아래 상태·결과 ID를 조회해서 화면에 연결할 수 있다.
이미지 생성, 서비스 토큰 차감·환급, 원문 수정 후 재분석은 별도 작업이다.

1. 기존 감정 선택 완료 API에서 꿈을 `COMPLETED`로 바꾸고 같은 트랜잭션에 `dream_generation_jobs`를 저장한다.
2. HTTP 응답은 저장 완료 직후 반환한다. AI 응답을 기다리지 않는다.
3. 워커는 커밋된 예약을 기본 2초 간격으로 조회한다. 롤백된 예약은 실행하지 않는다.
4. 짧은 트랜잭션에서 사용자·작업을 잠그고 claim을 확보한 뒤 트랜잭션을 종료한다.
5. `DreamStructureService.analyzeSource`의 Future에 완료 처리를 연결한다.
6. 분석 성공이면 작업을 `STORY / QUEUED`로 바꾼다. 다음 조회에서 `DreamStoryService.generateSource`를 시작한다.
7. 서사화까지 성공하면 `STORY / COMPLETED`, 어느 단계든 실패하면 해당 단계의 `FAILED`를 기록한다.
   생성 실패는 꿈의 저장완료 상태를 되돌리지 않는다.

초안·감정 선택 전 기록은 예약하지 않는다. 기존 완료 꿈을 배포 시 일괄 생성하지 않는다.
하나의 꿈에는 작업 행 하나만 저장하며, 원문·감정·외부 응답을 작업 테이블에 복사하지 않는다.
예약은 휘발성 이벤트가 아닌 DB 행이므로 저장 직후 프로세스가 종료돼도 남는다.
단, `local` 프로필은 H2 메모리 DB여서 재시작 시 모든 데이터가 사라진다. 운영 복구에는 영속 DB가 필요하다.

## 스레드와 동시 실행

외부 대기에는 `join`, `get`, `sleep`, 블로킹 HTTP 호출이나 대기용 풀 작업을 사용하지 않는다.
실제 Gateway는 기존 `CompletableFuture` 계약과 호출 예산을 그대로 사용한다.
검증·분석/서사화 저장은 #33의 `dream-result-*` 전용 풀, 예약 조회·작업 상태 기록은
`dream-auto-control-*` 제어 스레드 하나에서 짧게 처리한다. JPA를 제공자 콜백 스레드나 공용 풀에서 실행하지 않는다.
종료 처리에만 최대 5초 `awaitTermination`을 사용한다.

- 인스턴스마다 `MONGLE_DREAM_AI_MAX_CONCURRENT_CALLS`(기본 4)개까지만 자동 작업을 진행한다.
  기존 수동 분석·서사화와 같은 Gateway/결과 처리 슬롯도 적용된다.
- 한 조회는 최대 32개 후보만 읽는다. 다음 후보를 메모리에서 무제한 적재하지 않는다.
- 사용자 행 잠금과 유효한 작업 lease로 같은 사용자의 자동 작업은 한 번에 하나만 시작한다.
  다른 사용자는 AI 응답이 대기 중이어도 시작할 수 있다.
- 수동 API 전체의 사용자별 쿼터나 전체 인스턴스를 합친 외부 호출 한도까지 보장하지는 않는다.
  서버를 여러 개 운영하면 로컬 처리 한도는 인스턴스 수만큼 증가한다.
- 슬롯·Gateway 한도 초과는 기존 서비스처럼 실패로 기록한다. 자동으로 반복 호출하지 않는다.

## 수정·삭제·중복

자동 작업은 `sourceRevision`을 검증한다. 생성 제목이나 사용자 제목 변경으로 생기는 편집 `revision` 변화는 허용한다.
원문이 바뀌면 `SOURCE_CHANGED`로 중단하고 결과를 적용하지 않는다. 이후 자동 재분석·이미지 재생성·토큰 차감도 하지 않는다.
완료 후 원문이 바뀐 경우 이전 작업은 `COMPLETED`를 유지하고 상태 조회의 `sourceChanged=true`로 구분한다.
감정 수정 정책은 기존 Dream 정책을 따른다.

사용자 → 작업 순서로 잠그며, 새 claim의 UUID가 기존 콜백을 차단한다.
예약·진행·완료 상태의 retry 요청은 추가 예약을 만들지 않는다. 수동으로 생성 중이거나 이미 완료된 같은 출처 결과도 재사용한다.
꿈 삭제 시 같은 트랜잭션에서 예약을 제거한다. 늦은 콜백은 행이 없으면 종료하며 예약을 재생성하지 않는다.
기존 정책대로 원문은 삭제하고 이미 저장된 분석·서사화 결과는 보존한다.

## 재시작 복구와 재시도

작업 lease는 3분, 기존 분석·서사화 시도 lease는 2분이다.
서버 시작 후 대기 예약을 읽고, 만료된 진행 작업은 새 claim으로 회수한다.
작업 상태 기록이 실패하면 현재 claim을 유지해 이후 만료 복구 대상으로 남긴다.

| 저장된 단계 결과 | 복구 동작 |
| --- | --- |
| 결과/시도 없음 | 외부 호출 예약 전 종료된 경우이므로 해당 단계를 시작 |
| 같은 출처의 `COMPLETED` | 호출 없이 다음 단계 또는 전체 완료로 전환 |
| 유효한 `PROCESSING` | 2초 뒤 결과를 다시 확인, 중복 호출 없음 |
| 만료된 `PROCESSING` | `FAILED / RECOVERY_REQUIRED`, 사용자의 명시적 재시도 대기 |
| 회수 중 발견한 `FAILED` | 실패 코드 유지, 자동 재호출 없음 |
| 원문 버전 불일치 | `FAILED / SOURCE_CHANGED`, 기존 예약 재시도 거절 |

`RECOVERY_REQUIRED`는 제공자가 처리했지만 서버가 결과를 저장하지 못했을 수 있다는 뜻이다.
사용자가 명시적으로 재시도하면 사용자 잠금 안에서 만료된 해당 단계 시도를 실패로 종료한 뒤 재예약한다.
유효한 진행 시도는 종료하지 않으며, 만료 시도의 늦은 콜백은 기존 attempt 검사로 거절한다.
재시도하면 호출·비용이 다시 발생할 수 있다. 제공자와 DB 사이의 exactly-once나 환급을 보장하지 않는다.
이 정책은 자동으로 유료 호출을 반복하는 것보다 수동 확인 후 재시도하도록 한다.

## API 계약

Bearer Access JWT와 온보딩 완료가 필요하다. 응답은 `ApiResponse`와 `Cache-Control: no-store`를 사용한다.

| API | 응답 | 설명 |
| --- | --- | --- |
| 기존 `PUT /api/v1/dreams/{dreamId}/emotions` | 기존 꿈 응답 | 저장과 자동 작업 예약, 생성 완료를 기다리지 않음 |
| `GET /api/v1/dreams/{dreamId}/generation` | 200 | 자동 작업 상태·실패 코드·현재 결과 ID |
| `POST /api/v1/dreams/{dreamId}/generation/retry` | 202 | `{"revision":최신_꿈_revision}`; FAILED 작업만 재예약 |

상태 응답 데이터 예시:

```json
{
  "dreamId": 1,
  "sourceRevision": 1,
  "sourceChanged": false,
  "stage": "STORY",
  "status": "COMPLETED",
  "failureCode": null,
  "analysisId": 10,
  "storyId": 20
}
```

예약·진행·완료 상태에서도 retry는 현재 상태와 202를 반환한다. 202가 추가 AI 호출을 뜻하지는 않는다.
서사화 실패 후 retry는 완료 분석을 유지하고 서사화만 실행한다.
제목 자동 저장으로 꿈 revision이 바뀔 수 있으므로 retry 전에 기존 꿈 조회 API로 최신 revision을 가져온다.
타인·없는 꿈·자동 작업이 없는 이전 기록은 404, 누락/음수 revision은 400,
오래된 편집 revision·원문이 변경된 예약은 `DREAM_409_3`이다.

결과 ID가 있으면 기존 `GET /api/v1/analyses/{analysisId}` 및 `GET /api/v1/stories/{storyId}`로 조회한다.
진행/실패 중에도 ID가 존재할 수 있으므로 각 결과 API의 상태와 `sourceChanged`를 확인한다.
Archive에서 유료 생성 API를 조회용으로 호출하지 않는다.
일반 오류 코드는 기존 계약을 유지하고, 아래 코드는 자동 작업의 `failureCode`다.

| 코드 | 다음 행동 |
| --- | --- |
| `UNAVAILABLE` | 서버의 LINER 키/설정 확인 후 명시적 재시도 |
| `CALL_FAILED` / `INVALID_OUTPUT` / `PERSISTENCE_FAILED` | 원문은 저장됨, 실패 단계를 재시도 |
| `RECOVERY_REQUIRED` | 호출/로그 상태 확인 후 중복 비용 가능성을 알고 재시도 |
| `SOURCE_CHANGED` | 현재 원문과 이전 결과 구분, 별도 재분석 기능 사용 예정 |

## 설정과 DB 적용

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `MONGLE_DREAM_GENERATION_ENABLED` | true | 자동 워커 시작 여부. false여도 저장 시 예약은 유지 |
| `MONGLE_DREAM_GENERATION_POLL_INTERVAL` | 2s | 100ms~1분 범위 조회 간격 |
| `MONGLE_DREAM_AI_MAX_CONCURRENT_CALLS` | 4 | 기존 꿈 AI 처리 한도와 자동 워커 동시 진행 수 |

키 없이도 저장은 가능하며 자동 작업은 `UNAVAILABLE`로 실패한다. 키 설정 후 명시적 retry가 필요하다.
워커가 비활성화된 동안의 예약은 재활성화 후 처리된다. 배포 전 이를 확인한다.

신규 DB는 최신 `db/schema-initial.sql`을 사용한다.
기존 DB는 서비스 중지·백업 후 `db/migrations/20261008-dream-auto-generation.sql`을 1회 수동 적용한다.
`dream_generation_jobs` 테이블과 인덱스만 추가하며 기존 User·인증·AI 로그 SQL을 바꾸지 않는다.
Hibernate `validate`로 기존 테이블과 새 테이블을 확인한다. 앱이나 번들 적용 스크립트가 SQL을 실행하지 않는다.

## 검증

Java 21에서 유료 API 키 없이 실행한다.

```sh
./gradlew test --tests '*DreamGeneration*' --tests '*InitialSchemaValidationTest'
./gradlew test
git diff --check
```

추가 테스트는 저장·예약 롤백, 외부 Future 대기 중 반환, 전용 완료 스레드, 한도,
사용자별 예약, 중복 claim/retry, 단계별 실패/재시도, 출처 수정·삭제,
저장된 분석/서사화 복구, 만료·불명확 시도 수동 재시도, HTTP 권한·검증·no-store,
새 스키마 및 기존 DB 마이그레이션을 확인한다.
`test` 프로필은 워커 자동 시작을 끄고 각 테스트가 조회 시점을 직접 제어한다.

### 검증 기록

2026-10-08 기준 `f84ce77ba98f58effa438c894631fd07320efe28`의 전체 테스트는
개발자의 Mac 환경에서 재실행 후 성공한 것으로 확인했다. 전체 테스트 수는 별도 결과 파일로 확인하지 않았다.
이는 아래 추가 리뷰 수정의 테스트 통과를 의미하지 않는다.

저장된 단계의 실패 코드를 작업에 유지하고 감싸진 DB 장애를 복구 대상으로 남기는 수정에
실패 코드·DB 장애·상태 조회 장애·일반 호출 실패 회귀 테스트를 추가했다.
리뷰 수정 적용 후에는 Java 21에서 전체 테스트를 다시 실행한다.
적용 스크립트는 성공한 실행의 JUnit XML에서 기준 커밋, 테스트 수, 실패·오류·건너뜀 수를 읽어
아래 최종 검증 기록을 갱신한다. 이전 실패 기록이나 다른 PR의 테스트 수를 최종 결과로 사용하지 않는다.

<!-- review-verification-start -->
검증 시각: 2026-10-09 02:31:43 +0900

테스트 기준 커밋: `46411d2730ee40b629a767a24d7bd6ff6f567f48`

실행 Java: openjdk version "21.0.12.1" 2026-08-18 LTS

`./gradlew test --rerun-tasks`: 전체 332개, 실패 0, 오류 0, 건너뜀 0.
`git diff --check` 통과. 실제 유료 AI 호출은 사용하지 않았다.
<!-- review-verification-end -->
