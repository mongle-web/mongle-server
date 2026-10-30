# 월별 반복 장소·인물 (#111)

## API와 응답

`GET /api/v1/analysis-records/recurring-elements?month=2026-10`

온보딩 완료 회원의 Bearer 토큰으로 본인 데이터만 조회한다. month를 생략하면 한국 시간의 현재 월이며
기존 monthly와 같은 월 파싱·오류 응답을 사용한다. 잘못된 월은 HTTP400/COMMON_400_1,
미인증은401, 온보딩 전에는403이다. GET 요청이며 본문이 없고 Cache-Control:no-store다.

아래 값은 형태 설명용 예시다.

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "요청에 성공했습니다.",
  "data": {
    "month": "2026-10",
    "analyzedDreamCount": 7,
    "places": [{"name": "어린 시절 집", "dreamCount": 3}],
    "characters": [{"name": "가족", "dreamCount": 2}]
  },
  "errors": []
}
```

- places는 PLACE, characters는 CHARACTER 유형만 담는다. SYMBOL·EMOTION을 두 목록에 섞지 않는다.
- name은 해당 집계에 저장된 표시 이름, dreamCount는 해당 요소가 나타난 서로 다른 꿈 수다.
- 같은 꿈의 여러 장면에 같은 요소가 나타나도1건이다. 이름이 같더라도 장소와 인물은 별도로 집계한다.
- 서로 다른 꿈2개 이상에서 등장한 항목만 각 목록에 최대5개 반환한다. dreamCount 내림차순,
  동률이면 저장된 정규화 이름 오름차순(DB 문자열 정렬 기준)이다. 대표 표시 이름은 같은 집계의 name 최솟값이다.
- analyzedDreamCount는 해당 월의 유효한 성공 분석 꿈 수이며 반복 요소가 없는 꿈도 포함한다.
  두 배열 길이나 dreamCount의 합계가 아니다. 반복 근거가 없으면 두 목록이 모두 비어 있을 수 있다.
- 빈 달은 analyzedDreamCount=0, places=[], characters=[]다. 누락된 항목을 임의로 보충하지 않는다.

## 집계 근거와 변경

기간은 분석 생성 시각이 아니라 꿈 날짜 기준의 해당 월 첫날~마지막 날이다.
최근30일·최대10개를 사용하는 recent-context/종합 해석 대상과 구분한다.
현재 원본 꿈과 연결된 본인 소유 COMPLETED 기록의 마지막 공개 성공 분석을 사용한다.
초안·감정 선택 전·삭제·원본FK가 해제된 과거 분석·최초 실패/진행만 있는 분석은 제외한다.
결과 버전이 없더라도 COMPLETED이고 재생성 후보가 아닌 과거 성공 분석은 기존 월별 통계와 같이 읽는다.

기존 DreamElementNames의 저장된 normalized_name을 유형별로 집계한다. 정규화 키가 null/빈 값인
과거 요소는 다른 이름과 섞어 추정하지 않고 요소 목록에서 제외한다. 해당 분석 자체의 건수는 유지한다.
동의어·별칭을 같은 인물로 추론하지 않으며 이름을 새로 생성하지 않는다.

원문·감정 수정만으로 AI를 자동 호출하지 않는다. 새 분석·서사 재생성 진행/실패와 분석 후보 저장 중에는
유효한 이전 성공 분석을 계속 집계한다. 새 분석·서사가 함께 성공 확정되면 새 요소로 교체된다.
꿈을 삭제하면 다음 조회에서 제외한다. 세계관 고정 서사 버전의 요약과는 독립적이다.

## 프론트 연결

1. 월별 키워드·감정 통계와 같은 month를 요청한다.
2. data.places와 data.characters를 각 반복 장소/인물 영역에 연결한다.
3. name과 dreamCount를 표시하고 빈 배열이면 빈 상태 또는 영역 숨김을 사용한다.
4. 월 변경·꿈 삭제·분석 재생성 성공 후 해당 월 통계를 다시 조회한다.
5. 기존 GET /analysis-records/monthly의 keywords·emotions와 세계관 summary 필드는 그대로 사용한다.

Swagger 전용 이름은 MonthlyRecurringElementsResponse와 MonthlyRecurringElement다.
항목의 dreamCount와 전체 analyzedDreamCount는 기존 통계와 같은 integer(int64)다.
월별 키워드 MonthlyAnalysisKeyword나 세계관 WorldSummaryKeyword와 타입 이름을 공유하지 않는다.

## 구현·검증·배포

DB에서 count(distinct dream)과 그룹 집계를 수행하고 목록별5개만 읽는다. 전체 원문·분석 JSON이나
장면을 조회/직렬화하지 않는다. 세 집계는 같은 REPEATABLE_READ 읽기 트랜잭션에서 처리한다.
AI·S3·크레딧·결제·워커·비밀값을 새로 연결하지 않으며 DB 마이그레이션·환경변수 추가도 없다.
기존 monthly 응답과 종합 해석 생성/갱신 흐름은 유지한다.

H2/HTTP 검증과 동일한 집계 계약의 실제 MySQL8.4 테스트를 제공한다. 후자는 기존 일회용 로컬
MySQL 러너와 DML 전용 계정을 사용한다. 운영 RDS나 유료 AI를 호출하는 테스트가 아니다.
PR 머지·CI/CD 성공 후 운영 Swagger에 새 경로가 표시되면 프론트에서 연결한다.
코드 구현 완료를 운영 배포/실사용 검증 완료로 표시하지 않는다.
