# 꿈 분석 표시 메타데이터 (#19)

기반/PR 대상: `fix/17-dream-edit-policy` (#18). 작업 브랜치: `feat/19-dream-display-metadata`.
#14 통계 브랜치에 의존하지 않으며 부모 PR 병합을 기다리지 않고 개발한다.

## 생성 계약

기존 `StructureGenerator` 한 번의 호출에서 장면·요소와 제목·표시 키워드를 함께 받는다.
프롬프트 버전은 `scene-v2-display`이다. 출력은 `generatedTitle`, `displayKeywords`,
`elements`, `scenes` 네 필드를 필수로 갖는 JSON 객체 한 개다.
원문 지시문은 데이터로만 읽으며 JSON ID, 설명문·Markdown, 알 수 없는 필드·중복 키는 거절한다.

- generatedTitle: 원문 핵심 장면의 제목, 앞뒤 Unicode 공백 제거 후 1~20 코드포인트.
- displayKeywords: 중요한 순서대로 1~5개, 각각 앞뒤 Unicode 공백 제거 후 1~20 코드포인트.
- 대소문자·전각·중복 공백을 NFKC 기반으로 정규화하여 중복 키워드를 거절한다. 저장/화면 값은 원래 표현을 유지한다.
- 제목·키워드의 줄바꿈/제어문자는 거절한다. 범위·타입·필수 필드 위반은 `INVALID_OUTPUT`으로 실패한다.
- 보이지 않는 형식 문자(Cf: U+200B, BOM, 방향 제어 등)를 거절하며 공백·결합 부호만 있는 값도 거절한다.
  예외로 이모지 사이의 ZWJ(U+200D)는 유지한다. 앞 이모지의 VS16·피부색 수식자를 허용하며,
  ZWJ 단독·문자 사이·맨 앞/뒤·연속 ZWJ는 거절한다. 이모지 조합도 구성 코드포인트를 모두 20자 제한에 포함한다.
- 키워드는 원문·선택 감정의 근거를 쓰도록 프롬프트에 명시한다. 문자열 검증이 의미적 사실성을 보장하지는 않는다.
- 기존 요소 이름 기반 통계 계약은 유지한다. 표시용 키워드는 별도 화면 데이터이며 #14 집계를 바꾸지 않는다.

Gateway 실제 어댑터는 아직 연결하지 않는다. #5 연동 시 반드시 v2 프롬프트·스키마를 함께 적용해야 한다.
scene-v1 형식으로 새 출력을 반환하면 INVALID_OUTPUT이다. Gateway 입력 포트·AI 로그 필드는 바꾸지 않는다.
운영 기본 생성기는 여전히 unavailable(503)이며 가짜 생성기는 테스트 안에서만 제공한다.

## 저장·조회와 수정

분석의 generated_title과 순서 있는 dream_analysis_display_keywords에 스냅샷을 저장한다.
제목·키워드의 저장 컬럼은 VARCHAR(40)이다. H2가 보조 평면 문자를 UTF-16 두 단위로 계산하므로
이모지 20개도 저장할 수 있도록 여유를 둔다. API/AI 출력 제한은 계속 20 코드포인트이며 21개는 검증에서 거절한다.
장면·요소·키워드·분석 완료 상태·꿈 자동 제목은 같은 finish 트랜잭션에서 원자적으로 저장한다.
외부 AI 호출은 트랜잭션 밖이다. 사용자 행 락과 attemptId/sourceRevision 검사를 먼저 수행한다.
원문 변경(변경 후 복원 포함), 원문 삭제 또는 만료 시도는 새 제목·키워드·장면을 저장하지 않는다.
키워드 저장 실패도 제목·장면·요소까지 롤백하고 별도 실패 상태 저장 후 재시도할 수 있다.

자동 제목은 #17의 applyGeneratedTitle 정책을 사용한다. 동일 sourceRevision, title=null, edited=false일 때만 붙인다.
직접 정한 제목·생성 중 제목 수정·명시적인 빈 제목은 유지한다. 원문을 이미 편집한 기록도 보수적으로 자동 제목을 붙이지 않는다.
자동 생성만으로 edited가 true가 되지는 않는다. 기존 긴 제목은 그대로 보존한다.
자동 제목 저장은 optimistic revision을 증가시킬 수 있지만 AI 입력 sourceRevision은 증가시키지 않는다.
분석 응답의 dreamRevision 또는 최신 꿈 조회 revision을 다음 수정·삭제·서사화 요청에 전달한다.
분석 상태 변경만 있을 때는 기존처럼 revision을 증가시키지 않는다.

| 응답 | 추가 값 | 화면 사용 |
| --- | --- | --- |
| 분석 생성/조회 | generatedTitle, displayKeywords, dreamRevision | 생성 당시 결과와 최신 수정용 버전. sourceChanged/sourceDeleted 유지 |
| 꿈 단건/수정/작성 응답 | displayKeywords, analysisSourceChanged | title은 현재 사용자 제목. 키워드에 이미지 생성은 불필요 |
| 미완성 기록 목록 | displayKeywords=[], analysisSourceChanged=false | 이어쓰기용 응답 |

사용자가 제목을 비우면 화면은 DreamResponse.title=null을 그대로 사용한다.
AnalysisResponse.generatedTitle을 비운 제목의 대체 제목으로 쓰면 안 된다.
원문을 바꾼 뒤에도 이전 키워드는 유지하며 analysisSourceChanged=true로 수정 전 결과임을 표시한다.
원문 삭제 후 분석 generatedTitle·displayKeywords는 보존된다. 삭제된 원문의 dreamRevision은 null이다.
타인/없는 꿈·분석은 기존 404 정책을 유지한다.
캘린더·보관함 목록 API는 후속 작업이며 이 계약을 재사용한다.

## 기존 데이터·MySQL

이미 COMPLETED인 scene-v1 분석은 멱등 재조회하며 AI를 다시 호출하지 않는다.
기존 결과는 generatedTitle=null, displayKeywords=[]로 조회한다. 데이터를 추정해 보충하거나 기존 제목을 덮어쓰지 않는다.
신규 DB: 갱신된 schema-initial.sql. 기존 DB: #17까지 적용 후
`src/main/resources/db/migrations/20261006-dream-display-metadata.sql` 수동 실행.
앱/AI 호출을 중지하고 백업한 뒤 적용한다. DDL은 자동 커밋이며 실패 후 재실행해도 기존 결과를 갱신하지 않는다.
스크립트는 DB에 접속하지 않는다. MySQL 실제 적용 검증은 별도 테스트 DB에서 수행해야 한다.

## 검증

프롬프트·스키마와 실제 JSON 파서의 필수 필드·범위·중복·단일 객체·Unicode 경계를 대조한다.
가짜 생성기 통합 테스트에서 이미지 없는 상세/분석 HTTP 응답, 제목 수정 전/중/후,
기존 v1/초안, 원문 변경·복원·삭제, 만료 시도, 키워드 저장 실패 원자성과 재시도를 검증한다.
초기 SQL을 사용하는 ddl-auto=validate 테스트에 신규 컬럼·키워드 테이블을 포함한다.
