# 연결된 꿈 목록 카드 (#103)

## API와 응답

`GET /api/v1/worlds`의 기존 `items`, `hasNext`, `nextBeforeId` 형식과 폴더 필드는 유지한다.
각 항목에 현재 공개 완성본의 연결 정보와 기존 세계관 요약을 추가한다. 생성·수정 API와
`GET /api/v1/worlds/{worldId}`는 기존 `WorldFolderResponse`를 계속 사용한다.

| 필드 | 의미 |
| --- | --- |
| worldId, name, selectedDreamCount, revision, createdAt, updatedAt | 기존 폴더 메타데이터 |
| composedDreamCount | 현재 공개 완성본에 반영된 서로 다른 꿈 수. 생성 중인 후보 구성은 제외 |
| representativeDream | 공개 완성본에서 꿈 날짜·ID가 가장 이른 꿈의 dreamId·현재 title·dreamedAt. 없으면 null |
| summary | 기존 WorldSummaryResponse. 생성 제목·공통 키워드·반복 감정과 상태·출처 안내 |

다음은 형식 설명용 예시다. 현재 완성본은 3개 꿈이고, 표시 중인 요약은 이전 2개 꿈으로 만든 결과다.

```json
{
  "items": [
    {
      "worldId": 12,
      "name": "나의 세계관",
      "selectedDreamCount": 4,
      "revision": 7,
      "createdAt": "2026-10-01T09:00:00",
      "updatedAt": "2026-10-29T10:00:00",
      "composedDreamCount": 3,
      "representativeDream": {
        "dreamId": 21,
        "title": "파도 너머의 밤",
        "dreamedAt": "2026-10-01"
      },
      "summary": {
        "worldId": 12,
        "compositionRevision": 2,
        "resultCompositionRevision": 1,
        "generationVersion": 3,
        "status": "PENDING",
        "failureCode": null,
        "generatedTitle": "하늘을 건넌 바다",
        "commonKeywords": [{"keyword":"계단","dreamCount":2}],
        "recurringEmotions": [{"code":"HAPPY","label":"행복","dreamCount":2}],
        "sourceCount": 2,
        "limitedEvidence": false,
        "sourceChanged": false,
        "refreshNeeded": true,
        "hasPreviousResult": true,
        "generatedAt": "2026-10-01T01:00:00Z",
        "promptVersion": "world-summary-v1"
      }
    }
  ],
  "hasNext": false,
  "nextBeforeId": null
}
```

## 화면 연결

- 카드 제목: `summary.generatedTitle`이 있으면 표시하고 없으면 `name`을 사용한다. 폴더 이름을 AI 제목으로 저장·변경하지 않는다.
- 날짜: `representativeDream.dreamedAt`을 사용한다. 대표 꿈이 없으면 날짜를 생략한다.
- 연결 설명: composedDreamCount가 N이면 대표 꿈 제목과 '외 N-1개의 꿈'으로 표시한다. 총5개면 '파도 너머의 밤 외 4개의 꿈을 연결했어요'다.
  1개면 '대표 꿈 제목을 연결했어요'처럼 별도 문구를 사용하고 0개면 연결된 꿈이 없는 상태를 표시한다. 대표 제목 null의 대체 문구는 프론트에서 정한다.
- 태그: `summary.commonKeywords[].keyword`를 사용하며 근거가 없으면 빈 목록 그대로 표시한다. dreamCount는 해당 키워드를 포함한 요약 출처 꿈 수다.
- `summary.sourceCount`는 요약을 만들 때의 출처 수다. 현재 연결 개수는 composedDreamCount, 체크한 입력 후보 개수는 selectedDreamCount를 사용한다.
- `summary.refreshNeeded/sourceChanged/hasPreviousResult`로 이전 구성·수정 전 결과임을 안내한다. 생성 중·실패에도 유효한 이전 제목·키워드는 유지한다.
- 삭제는 기존 worldId와 최신 폴더 revision을 사용한다. 목록 조회는 삭제·생성·재시도를 실행하지 않는다.

## 상태·삭제·조회 계약

- 빈 폴더와 선택만 한 폴더는 composedDreamCount=0, representativeDream=null, summary.status=NOT_GENERATED다.
- 공개 완성본에서 꿈이 삭제되어 0개가 되면 summary.status=EMPTY다. 0~1개여도 폴더와 목록 카드를 유지한다.
- 요약의 현재 성공 출처에 없는 꿈/서사 버전이 확인되면 그 결과의 제목·키워드·감정·출처 수와 결과 메타데이터를 숨긴다. 기존 [요약 삭제 계약](world-summary.md)을 따른다.
- 목록은 읽기 전용이다. 만료된 요약 시도는 FAILED/ATTEMPT_EXPIRED로 표시하지만 DB 상태 변경·후보 정리·새 작업 접수는 하지 않는다. 기존 단건 GET·워커의 만료 복구 계약을 유지한다.
- 새 결과가 필요한 경우 기존 세계관 완성/부분 반영 및 summary POST 계약을 사용한다. 목록을 새로고침해도 AI·크레딧·이미지 저장소를 호출하지 않는다.
- 폴더 목록·구성·대표 꿈·요약은 같은 REPEATABLE_READ 트랜잭션에서 읽는다. 여러 요청에 걸친 고정 스냅샷은 보장하지 않는다.
- 기존 인증·온보딩·소유권과 `Cache-Control: no-store`를 유지한다. size 기본20·최대50, beforeId 및 폴더 이름 query 검색은 동일하다.
  AI 생성 제목·공통 키워드 검색으로 범위를 확장하지 않는다.

## 구현·검증

현재 페이지에 반환할 폴더만 일괄 조회한다. 비어 있지 않은 페이지는 폴더·구성·요약의 JPA 조회3회와
공개 출처 개수·유효성·누락 여부·대표 꿈의 JDBC 조회4회로 처리하며 카드마다 추가 조회하지 않는다.
본문·분석 JSON을 로딩해 재집계하거나 폴더별 summary GET을 반복 호출하지 않는다. 빈 페이지는 폴더 조회만 한다.

기존 V5 세계관 요약 테이블을 사용하며 새 DB 마이그레이션은 없다. 기존 요약과 상태 변환 규칙을 공유한다.
H2·모의 Provider로 목록/단건 결과 일치, 선택/공개/요약 개수 분리, 후보 제외, 이전 결과 보존,
원문 변경·출처 삭제·누락·0~1개·미생성·만료, HTTP 응답 및 JPA/JDBC 조회 횟수를 검증한다.
실제 MySQL·LINER·운영 배포 검증은 별도다.
