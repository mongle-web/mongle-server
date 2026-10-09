# 꿈 캘린더 월별 조회

`GET /api/v1/archives/calendar?month=YYYY-MM`은 선택한 월에 꿈을 기록한 날짜와 기록 일수를 반환한다.
월간·주간 캘린더의 기록 표시와 상단 기록 일수 문구에 사용하며, 날짜별 카드는 기존 Archive API로 조회한다.

## 요청·응답 계약

온보딩을 완료한 사용자의 Bearer JWT가 필요하다. 조회 소유자는 JWT의 subject로 결정하며 사용자 ID를
쿼리로 받지 않는다. 성공 응답은 기존 `ApiResponse`의 `data`에 `DreamCalendarResponse`를 넣고
`Cache-Control: no-store`를 적용한다.

```http
GET /api/v1/archives/calendar?month=2026-09
Authorization: Bearer {accessToken}
```

아래 `data`는 응답 형식을 설명하는 예시이며 실제 사용자 데이터가 아니다.

```json
{
  "month": "2026-09",
  "recordedDayCount": 4,
  "recordedDates": [
    "2026-09-03",
    "2026-09-07",
    "2026-09-08",
    "2026-09-16"
  ]
}
```

| 필드 | 의미 |
| --- | --- |
| `month` | 요청한 월, `YYYY-MM` 형식 |
| `recordedDayCount` | 해당 월에 완성한 꿈을 기록한 서로 다른 날짜 수 |
| `recordedDates` | 기록이 있는 날짜 전체, 날짜 오름차순 |

- `month`는 필수이며 네 자리 양수 연도와 두 자리 월을 전달한다. 생략·빈 값·잘못된 형식·존재하지 않는 월은
  400 `ARCHIVE_400_1`이다. 로그인하지 않은 요청은 기존 인증 정책에 따라 401이다.
- 조회 기준은 DB 생성 시각이 아닌 꿈을 꾼 날짜 `dreamedAt`이다. 과거 날짜를 오늘 작성해도 그 과거 월에 집계된다.
- 감정 선택을 완료한 `COMPLETED` 기록만 포함한다. AI 분석·서사·이미지가 없어도 기록한 날짜로 표시한다.
- `DRAFT`, `EMOTION_PENDING`, 삭제한 기록과 타인 기록은 제외한다.
- 기록이 없으면 `recordedDayCount=0`, `recordedDates=[]`로 200을 반환한다. 미래 월도 같은 규칙을 따른다.
- 페이지네이션 없이 해당 월 전체를 반환한다. 날짜 수는 월 길이에 따라 최대 28~31개이며 윤년을 반영한다.

## 화면에서 사용하는 흐름

1. 화면에 표시할 월로 캘린더 API를 조회한다. `recordedDates`에 있는 날짜를 기록이 있는 날짜로 표시하고
   `recordedDayCount`를 월별 기록 일수 문구에 사용한다.
2. 이전·다음 월 이동이나 날짜 선택 모달 확인 시 변경한 월로 다시 조회한다.
3. 주간 접힘·월간 펼침은 같은 월의 날짜 목록을 사용한다. 요일 배치, 선택 날짜, 오늘 표시와 앞뒤 달의
   회색 날짜는 프론트에서 처리한다. 이 API는 요청한 월의 기록 날짜만 반환한다.
4. 날짜를 선택하면 `GET /api/v1/archives?date=2026-09-16`으로 해당 날짜의 카드를 조회한다.
   카드의 `dreamId`로 기존 Archive 상세 조회 API에 접근한다.
5. 꿈의 감정 선택을 완료하거나 삭제한 후 캘린더와 선택 날짜 카드를 다시 조회한다.
   제목·원문·감정 수정은 꿈 날짜를 바꾸지 않으므로 캘린더의 기록 일수는 유지된다.

## 구현 흐름과 조회 비용

`ArchiveController.calendar()` → `ArchiveService.calendar()` → `ArchiveMonth.parse()` →
`ArchiveQueryRepository.findRecordedDates()` → `DreamCalendarResponse` 순서로 처리한다.

- `ArchiveMonth`는 기존 Archive 월별 목록과 공유하는 월 검증·첫날·마지막 날 계산 로직이다.
- `ArchiveService`의 읽기 전용 트랜잭션에서 소유자·완성 상태·날짜 범위 조건을 적용한다.
- 조회는 `DISTINCT dreamedAt` 날짜 쿼리 한 번이다. Dream 엔티티나 분석·감정·이미지 컬렉션을 로딩하지 않는다.
- 한 번 조회한 날짜 목록의 크기로 집계하므로 별도 count 쿼리를 실행하지 않는다.
- 새 테이블이나 마이그레이션을 추가하지 않으며 기존 `idx_dreams_user_status_date_id` 인덱스를 활용할 수 있다.
- AI 호출과 외부 이미지 URL 발급은 수행하지 않는다. 이미지가 필요한 카드는 기존 Archive·이미지 API를 사용한다.

## 검증

`ArchiveIntegrationTest`에서 실제 HTTP 인증과 H2/JPA를 사용해 완성 상태·소유권·생성 결과 유무,
작성 완료·삭제 후 날짜와 집계 갱신, 31일 전체 조회, 날짜 오름차순, 윤년·연도 경계·빈 달,
월 입력 오류·인증·Swagger를 검증한다. 31일 조회에 SQL 한 번만 실행되고 엔티티 로딩이 없는지도 확인한다.
같은 테스트 클래스에서 기존 Archive 목록·상세·정렬·커서 동작도 함께 검증한다.
