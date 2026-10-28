# 최근 꿈 종합 해석

`recent-context`의 개별 분석 목록과 별개로, 최근 분석을 묶은 한국어 해석 문단을 제공한다. 무료이며 결제·크레딧·이미지 Provider/S3와 독립적이다. 생성은 기존 LINER Gateway와 동시 호출/결과 저장 제한을 공유한다.

## API와 첫 진입

업무 API와 동일하게 온보딩을 완료한 회원의 Bearer 토큰이 필요하다. 사용자 ID나 꿈 ID를 요청에서 받지 않는다. 본인 계정의 대상만 서버가 선정한다.

| 메서드 | 경로 | 역할 |
|---|---|---|
| GET | `/api/v1/analysis-records/interpretation` | AI 호출 없이 결과·현재 대상 수·갱신 필요 여부 조회 |
| POST | `/api/v1/analysis-records/interpretation` | 첫 진입의 최초 생성 또는 최초 실패 재시도 |
| POST | `/api/v1/analysis-records/interpretation/refresh` | 변경 안내 후 명시적 갱신/갱신 실패 재시도 |

POST 두 개는 요청 본문이 없다. 화면 첫 진입에서 최초 POST를 호출하면 이전 성공 전에는 생성하고, 이전 성공 후에는 저장 결과를 반환한다. GET에서 `NOT_GENERATED`를 확인한 뒤 POST하는 흐름도 가능하다. GET 자체는 생성을 시작하지 않는다.

신규 꿈/분석 변경 뒤에도 최초 POST를 다시 호출하는 것만으로 자동 갱신하지 않는다. `refreshNeeded=true`를 표시하고 버튼에서 `/refresh`를 호출한다. 동일한 대상·성공 분석 결과·프롬프트 버전의 완성본은 `/refresh`에서도 재사용한다. 같은 대상을 계속 눌러도 무료 AI 호출이 반복되지 않는다.

최초 POST 응답은 생성 완료까지 비동기 대기할 수 있다. 중복 요청에서 이미 진행 중인 작업을 만나면 즉시 HTTP202를 반환한다. 요청 연결이 끊어지거나 프론트가 먼저 타임아웃돼도 작업을 중복 생성하지 않고 GET으로 상태를 복구한다. 무한 자동 재시도는 하지 않는다. 완료/실패/대상 없음은 HTTP200이다. `success=true`는 상태 조회/요청 처리 성공이며, 생성 성공 여부는 `data.status`로 판단한다.

## 응답

공통 `ApiResponse`의 `data`:

```json
{
  "status": "COMPLETED",
  "failureCode": null,
  "interpretation": "관찰 자료가 적습니다. 바다와 자유로운 이동 장면이 나타납니다.",
  "generatedAt": "2026-10-28T03:00:00Z",
  "promptVersion": "recent-interpretation-v1",
  "refreshNeeded": false,
  "limitedEvidence": true,
  "from": "2026-09-29",
  "to": "2026-10-28",
  "targetCount": 1,
  "sources": []
}
```

위 `sources`는 구조를 줄인 예시다. 실제 완성본에는 꿈 ID·분석 ID·꿈 날짜·현재 입력 `sourceRevision`·성공 분석 `analysisRevision`·통합 성공 버전 ID(기존 분석은 null 가능)·분석 지문이 들어간다. 생성 당시 출처이며 현재 대상과 다를 수 있다. 꿈 원문·분석 본문·모델 인증값은 응답에 넣지 않는다.

| status | 화면 처리 |
|---|---|
| EMPTY | 현재 분석 대상 없음. 본문·완성본 출처는 반환하지 않고 생성하지 않는다 |
| NOT_GENERATED | 대상이 있으나 아직 시도 전. 최초 POST로 생성 |
| PROCESSING | 생성 중. 이전 본문이 있으면 보존하며 GET으로 상태 확인 |
| COMPLETED | 완성본 표시. refreshNeeded=true면 변경 안내와 갱신 버튼 |
| FAILED | 실패 안내와 재시도. 유효한 이전 본문이 있으면 함께 표시 가능 |

`from/to/targetCount`는 현재 한국 날짜 기준의 대상 범위다. `generatedAt/sources/promptVersion`은 표시하는 완성본의 정보다. `limitedEvidence`는 본문이 있으면 해당 완성본의 출처 3건 미만 여부, 본문이 없으면 현재 대상 3건 미만 여부다. 1~2건도 생성 가능하며 프롬프트에서 자료가 적다는 점을 먼저 알리도록 한다.

`failureCode`는 `CALL_FAILED`, `INVALID_OUTPUT`, `SOURCE_CHANGED`, `SOURCE_DELETED`, `ATTEMPT_EXPIRED`, `PERSISTENCE_FAILED` 중 하나다. 제공자의 오류 원문은 전달하지 않는다. 최초 성공 전 FAILED는 최초 POST로, 이전 성공 후 FAILED는 `/refresh`로 재시도한다. 삭제로 기존 본문이 제거돼도 이전 성공 이력은 남겨 재진입 자동 생성을 막는다.

설정/키 미준비는 HTTP503 `INTERPRETATION_503_1`, 분석 입력 JSON이 120,000 UTF-16 단위를 넘으면 HTTP400 `INTERPRETATION_400_1`로 새 호출을 시작하지 않는다. 저장 실패 응답은 HTTP502 `INTERPRETATION_502_2`다. 인증/온보딩 공통401/403 계약도 적용한다.

## 대상·원본 변경·삭제

- 기존 recent-context와 동일한 성공 분석을 꿈 날짜 내림차순, 분석 ID 내림차순으로 최근 30일·최대10건 선택한다. 꿈 FK가 분리되었거나 삭제된 분석은 제외한다.
- 개별 분석 재생성이 진행 중이거나 실패해도 이전에 공개한 성공 결과가 있다면 그 성공 결과를 사용한다. 수정 후 아직 분석을 다시 생성하지 않았다면 보관된 성공 분석이 입력이다. 새로운 원문을 몰래 분석하거나 개별 분석을 재생성하지 않는다.
- 대상 ID·현재 원문/감정 입력 revision·성공 분석 revision/내용·통합 성공 버전·프롬프트 버전을 비교한다. 제목만 수정하거나 하루가 지나도 실제 대상/결과가 동일하면 갱신을 요구하지 않는다.
- 원문/감정 수정이나 대상 변경은 기존 완성본을 자동 교체하지 않는다. 생성 도중 바뀌면 새 응답을 폐기하고 실패 상태를 기록한다.
- 꿈 영구 삭제는 같은 사용자 잠금·삭제 트랜잭션에서 관련 종합 해석 본문과 출처를 제거하고 관련 진행 중 시도를 무효화한다. 롤백 시 꿈과 종합 해석 정리도 함께 롤백한다. GET에서도 출처 생존 여부를 재검사한다.
- 이전 결과가 유효하면 생성/검증/저장 실패에도 보존한다. 삭제된 꿈의 문구를 포함할 수 있는 본문은 보존·노출하지 않는다.

## 실행과 운영 적용

```dotenv
MONGLE_RECENT_INTERPRETATION_ENABLED=false
```

기본은 false다. 배포만으로 LINER 유료 호출을 활성화하지 않는다. GET·기존 결과 재사용·빈 상태는 비활성일 때도 동작한다. 운영에서 `LINER_API_KEY`와 모델/응답 계약을 확인한 뒤 인프라 담당이 위 값을 true로 설정하고 기존 절차로 서비스를 재시작한다. `MONGLE_DREAM_GENERATION_ENABLED`는 개별 분석/서사 자동 워커 설정으로, 이 기능의 활성화 플래그와 별개다. OpenAI/S3/크레딧/결제 플래그를 함께 켤 필요는 없다.

외부 호출 중 DB 트랜잭션·행 잠금·대기 스레드를 유지하지 않는다. 회원별 예약과 완료만 기존 사용자 행 잠금으로 직렬화하고, 전체 호출·완료 작업 수는 기존 DreamGenerationResources/DreamAiGateway로 제한한다. 외부 호출은 기존 LINER 전체 예산(<2분)을 따르고 예약은2분이다. 재기동/실패로 남은 진행 상태는 다음 GET/POST에서 lease 만료를 검사해 재시도할 수 있게 한다. 늦은 이전 시도 응답은 최신 결과를 덮어쓰지 않는다.

`V2__recent_interpretations.sql`은 기존33개 업무 테이블에 신규 테이블1개만 추가한다. 이미 등록한 baseline1을 다시 실행하지 않는다. 기존 Flyway CD가 JAR 전환 전에 V2를 적용한다. 운영에서 수동 초기화 SQL을 재실행하거나 ddl-auto를 변경하지 않는다. 배포 후 업무 테이블은34개(+Flyway 이력1개)다. 병렬 PR도 새 마이그레이션을 추가하면 머지 전에 버전 충돌을 조정한다.

V2는 기존 JAR와 호환되는 추가 변경이다. 앱 롤백은 테이블을 삭제하지 않으며 후속 커밋에서도 적용한 SQL 파일을 그대로 보존한다.

## 검증

H2 HTTP/서비스 회귀는 최초 생성·재사용·수동 갱신·3건 미만·한국 날짜 경계·권한·중복/동시 예약·실패·기존 본문 보존·삭제·늦은 응답·Swagger 계약을 검증한다. 네이티브 MySQL8.4는 V1 baseline→V2, 기존 데이터 보존, DML 전용 앱의 Hibernate validate/Unicode 저장/동시 잠금/삭제를 검증한다. 실제 LINER 호출 품질·운영 계정·프론트 화면은 별도 실환경 확인 대상이다.
