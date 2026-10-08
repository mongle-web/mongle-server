# Archive 목록·상세 조회

이번 기능은 나의 꿈 카드와 기록 상세 화면에 필요한 읽기 API를 제공한다. 감정 선택까지 완료한
`COMPLETED` 기록을 표시하며, 분석·서사·이미지 생성 여부는 포함 조건이 아니다.
`DRAFT`, `EMOTION_PENDING`, 삭제한 꿈과 타인 기록은 제외한다.
월별 캘린더 날짜 표시와 기록 일수 집계는 후속 이슈 범위다.

## 호출과 화면 연결

두 API 모두 온보딩을 완료한 사용자의 Bearer JWT가 필요하다. 사용자 ID는 요청으로 받지 않고
JWT의 subject에서 결정한다. 성공 응답은 기존 `ApiResponse`의 `data`에 아래 DTO를 넣으며
`Cache-Control: no-store`를 적용한다.

| 화면/행동 | API | 사용 정보 |
| --- | --- | --- |
| 전체 Archive | `GET /api/v1/archives` | `data.items`의 카드 정보 |
| 특정 월 목록 | `GET /api/v1/archives?month=2026-09` | 꿈을 꾼 날짜 기준으로 해당 월 조회 |
| 선택한 날짜의 카드 | `GET /api/v1/archives?date=2026-09-16` | 해당 날짜의 완성 기록 |
| 기록 상세 | `GET /api/v1/archives/{dreamId}` | 공통 정보 `dream`, 원문 `originalText`, 감정 `emotions` |
| 꿈 수정 | 기존 `PATCH /api/v1/dreams/{dreamId}` | 상세에서 받은 최신 `dream.revision` 전달 |
| 꿈 삭제 | 기존 `DELETE /api/v1/dreams/{dreamId}?revision={revision}` | 목록/상세에서 받은 최신 버전 전달 |
| 이미지 표시 | 기존 `GET /api/v1/images/{imageId}/download-url` | `image.hasResult=true`일 때 임시 URL 발급 |

Archive 조회는 AI나 외부 이미지 저장소를 호출하지 않는다. 이미지가 없거나 저장소가 연결되지 않아도
기록을 읽을 수 있다. 임시 이미지 URL은 필요할 때 별도 API로 발급한다. 이 API의 유효기간과 저장소 오류는
기존 이미지 계약을 따른다. 서사 본문·분석 장면과 요소는 기존 생성 결과 API에서 조회한다.

## 조회 조건과 페이지

- `month`는 `YYYY-MM`, `date`는 `YYYY-MM-DD` 형식이다. 둘을 함께 전달하면 400이다.
- 둘 다 생략하면 내 완성 기록 전체를 조회한다. 날짜 기준은 `createdAt`이 아닌 `dreamedAt`이다.
- 정렬은 `dreamedAt DESC, dreamId DESC`다. 날짜당 하나라는 기존 저장 정책을 유지한다.
- `size` 기본값은 20, 허용 범위는 1~50이다.
- 첫 요청에는 `cursor`를 생략한다. `hasNext=true`이면 받은 `nextCursor`와 **같은 월/날짜 필터**를
  사용해 다음 페이지를 조회한다. 필터를 바꾸면 커서를 버리고 첫 페이지부터 조회한다.
- 커서는 클라이언트가 해석하거나 만들어낼 필요가 없는 위치 값이다. 권한 토큰은 아니며,
  모든 조회에 인증된 소유자 조건을 적용한다. 다른 필터의 커서·잘못된 커서는 400이다.
- 마지막 페이지는 `hasNext=false`, `nextCursor=null`이다. 빈 결과도 200과 빈 `items`를 반환한다.
- 앞쪽 기록이 추가되거나 이전 페이지의 마지막 기록이 삭제되어도 다음 페이지 경계가 밀리지 않는다.
  조회 도중 상태가 변경될 수 있으므로 여러 페이지가 하나의 고정된 스냅샷을 보장하지는 않는다.

아래는 **응답 형식을 설명하기 위한 예시**이며 실제 사용자 데이터가 아니다. 생성 결과가 없어도 카드가 존재한다.

```json
{
  "items": [
    {
      "dreamId": 12,
      "dreamedAt": "2026-09-16",
      "title": null,
      "displayKeywords": [],
      "edited": false,
      "revision": 1,
      "sourceRevision": 1,
      "analysis": null,
      "story": null,
      "image": null
    }
  ],
  "hasNext": false,
  "nextCursor": null
}
```

목록에는 원문, 생성 본문, 저장소 키, 서명 URL을 넣지 않는다. 제목이 아직 없으면 `title=null`이며
표시용 대체 문구는 프론트에서 선택한다. `displayKeywords`는 완료한 분석의 AI 키워드다.

상세의 `data`는 다음 구조다. `dream`은 위 카드와 같은 구조이고, 감정은 순위 없는 집합을
서버의 enum 순서로 반환한다.

```json
{
  "dream": {
    "dreamId": 12,
    "dreamedAt": "2026-09-16",
    "title": null,
    "displayKeywords": [],
    "edited": false,
    "revision": 1,
    "sourceRevision": 1,
    "analysis": null,
    "story": null,
    "image": null
  },
  "originalText": "회의에 늦는 꿈을 꿨어요.",
  "emotions": ["ANXIOUS"],
  "createdAt": "2026-09-16T09:00:00",
  "updatedAt": "2026-09-16T09:00:00"
}
```

## 생성 상태를 표시하는 방법

생성을 요청하지 않은 객체는 `null`이다. 요청한 객체는 각각 상태·ID·실패 코드를 반환한다.

| 객체 | 주요 필드 | 의미 |
| --- | --- | --- |
| `analysis` | `analysisId`, `status`, `failureCode`, `sourceChanged` | 현재 기록과 분석 입력 버전이 달라졌는지 |
| `story` | `storyId`, `storyVersion`, `status`, `failureCode`, `hasResult`, `hasPreviousResult`, `resultRevision`, `sourceChanged` | 실제로 보존된 이야기 결과가 있는지, 어느 입력에서 만들어졌는지 |
| `image` | `imageId`, `imageVersion`, 위 결과 필드, `storyChanged`, `contentType`, `width`, `height` | 보존된 파일 여부와 출처 이야기 변경 여부 |

`status`는 최근 생성 시도의 상태다. 재생성 진행 중이거나 실패해도 이전 성공 결과가 남을 수 있어
`status`만으로 표시 여부를 결정하면 안 된다. `hasResult=true`이면 표시할 결과가 있고,
`hasPreviousResult=true`이면 현재 시도가 완료 상태가 아니면서 이전 성공 결과를 보존 중이다.
`resultRevision`과 이미지 이야기 해시는 실제 표시 결과를 기준으로 비교한다. 실패한 재시도만으로
이야기가 바뀌었다고 표시하지 않는다.

원문·감정이 바뀌면 `sourceChanged=true`로 이전 입력의 결과임을 표시한다. 실제 이야기 본문이 바뀌면
기존 이미지에 `storyChanged=true`가 표시된다. 기존 결과를 자동으로 지우거나 AI 생성을 다시 실행하지 않는다.

## 수정과 삭제 정책

기존 수정 API로 제목·원문·감정을 변경한다. 미전달 필드는 유지하며 날짜는 변경할 수 없다.

```json
{
  "revision": 1,
  "title": "지각하는 꿈",
  "originalText": "회의에 늦어서 당황하는 꿈을 꿨어요.",
  "emotions": ["ANXIOUS", "CONFUSED"]
}
```

- 제목은 최대 20자이며 `null`, 빈 문자열, 공백만 전달하면 제거한다. 원문은 비어 있지 않은 최대 500자다.
  글자 수는 기존 정책대로 Unicode code point 기준이다.
- 감정은 기존 7종 `HAPPY`, `CALM`, `EXCITED`, `SAD`, `ANXIOUS`, `ANGRY`, `CONFUSED` 중
  중복 없는 1~3개다. 키워드는 직접 수정하지 않는다.
- 실제 변경이 있을 때만 `edited=true`가 된다. 프론트에서 이 값으로 ‘수정됨’을 표시한다.
  같은 값 재전달·감정 순서 변경은 수정 표시와 버전을 바꾸지 않는다. 이미 있는 수정 표시는 유지한다.
- 제목 변경은 `revision`만 증가한다. 원문·감정 변경은 `sourceRevision`도 한 번 증가한다.
  변경 후에는 수정 API 응답의 최신 `revision`을 다시 사용한다. 오래된 버전은 기존 `DREAM_409_3`이다.
- 이전 분석·키워드·서사·이미지는 보존한다. 생성 중 원문·감정이 바뀌면 기존 생성 완료 시점의
  버전 검증이 결과 반영을 막는다. 재분석·재생성 정책은 해당 생성 API의 계약을 따른다.
- 삭제는 기존 정책대로 원문·감정을 영구 삭제하고 분석의 꿈 FK를 해제한다. 이후 Archive에서 제외하지만
  보존된 분석·서사·이미지까지 물리 삭제하는 기능은 이번에 추가하지 않는다.

## 구현을 읽는 순서

1. `domain/archive/controller/ArchiveController.java`: GET 경로, 인증 사용자, 공통 응답.
2. `domain/archive/dto/request/ArchiveSearch.java`: 날짜·크기·커서 검증과 페이지 경계.
3. `domain/archive/service/ArchiveService.java`: 읽기 트랜잭션, 배치 결과 조립, 수정·출처 표시.
4. `domain/archive/repository/ArchiveQueryRepository.java`: 소유권과 완성 상태를 제한하는 실제 JPQL.
5. `domain/archive/dto/response/`: 목록·상세·생성 상태의 응답 구조.
6. 기존 `DreamService.update()` → `Dream.update()`: 감정 수정, 실제 변경 감지, 입력 버전 증가.

목록은 먼저 `size+1`개의 꿈을 조회하고, 반환할 카드에 해당하는 분석·서사·이미지를 배치로 읽는다.
관련 결과가 모두 있어도 조회 쿼리는 최대 4개다. 페이지 조회에 컬렉션 fetch join을 사용하지 않아
전체 데이터를 메모리에서 페이지로 자르는 문제를 피한다. 상세는 단건 감정을 함께 로딩한다.

## DB 적용과 검증

`dreams(user_id, record_status, dreamed_at, id)` 인덱스를 엔티티와 초기 스키마에 추가했다.
새 DB는 `db/schema-initial.sql`에 포함되어 있다. 기존 MySQL DB는
`db/migrations/20261008-archive-query-index.sql`을 테이블 규모와 잠금 영향을 확인한 뒤 수동 적용한다.
이 스크립트는 같은 이름의 인덱스가 이미 있으면 생성하지 않는다. 자동 마이그레이션이나 외부 DB 적용은 수행하지 않았다.

`ArchiveIntegrationTest`는 실제 HTTP 인증과 H2/JPA를 사용해 완성 기록 필터, 월·날짜와 윤년,
커서 경계 기록 삭제, 권한, 수정·삭제 연결, 보존 결과의 출처 표시, Swagger, 조회 쿼리 수를 확인한다.
이미지 URL은 테스트용 저장소 구현으로 검증하며 실제 LINER·이미지 Provider·외부 저장소를 호출하지 않는다.
H2에서 초기 스키마를 검증했으며 MySQL 마이그레이션을 실제 운영 DB에서 실행한 검증은 포함하지 않는다.
