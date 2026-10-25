# 세계관 폴더·꿈 선택 API (#58)

분기와 PR base는 `feat/56-openai-image-provider`(#57)다. 선행 PR의 develop 병합을 가정하지 않는다.

## 구현 범위

폴더 이름·선택 목록을 무료로 편집한다. 생성·재생성·연결 복구를 예약하거나 크레딧을 차감하지 않는다.
선택 목록은 앞으로 생성할 세계관의 입력 후보이며 완성 세계관의 구성이 아니다.
완성 세계관·부분 반영·전체 이미지·크레딧·결제 API는 별도 구현 범위다.

- 이름은 공백만 입력할 수 없고 최대 **100 Unicode 코드포인트**다. 이모지 하나도 한 글자로 세며 앞뒤 공백은 보존한다.
- 이름 중복은 허용한다. 선택한 꿈의 총개수 상한은 없다. 폴더가 0~1개의 꿈을 가져도 보존한다.
- 내 `COMPLETED` 꿈만 선택한다. 분석·서사·이미지의 성공 여부와 무관하고 생성 중에도 선택할 수 있다.
- 선택 목록은 불변인 `dreamedAt` 오름차순, 같은 날짜면 꿈 ID 오름차순이다. 수동 순서 변경은 없다.
- 선택 조회의 원문·제목은 **현재 원본 값**이다. AI 성공 버전이나 완성 세계관의 스냅샷으로 사용하지 않는다.
- 신규 세계관·전체 이미지의 최소 꿈 수 2개 검사는 후속 생성 API에서 적용한다.

## HTTP 계약

기존 `ApiResponse<T>` 봉투와 온보딩을 마친 Bearer JWT를 사용한다. 모든 응답은 `Cache-Control: no-store`다.
회원 ID를 요청에서 받지 않는다. 없는 폴더와 타인 폴더는 같은 `WORLD_404_1`, 없는 꿈과 타인 꿈은 같은 `DREAM_404_1`이다.

| 메서드 | 경로 | 입력 | 성공 data |
| --- | --- | --- | --- |
| POST | `/api/v1/worlds` | JSON `{ "name": "바다의 세계" }` | 201 `WorldFolderResponse`, Location 헤더 |
| GET | `/api/v1/worlds` | `size=20`, 선택 `beforeId` | `WorldFolderPage` |
| GET | `/api/v1/worlds/{worldId}` | 경로 ID | `WorldFolderResponse` |
| PATCH | `/api/v1/worlds/{worldId}` | JSON `{ "name": "새 이름", "revision": 0 }` | `WorldFolderResponse` |
| DELETE | `/api/v1/worlds/{worldId}` | 쿼리 `revision` 필수 | `null` |
| GET | `/api/v1/worlds/{worldId}/dreams` | `size=20`, 선택 `cursor` | `SelectedDreamPage` |
| PUT | `/api/v1/worlds/{worldId}/dreams/{dreamId}` | JSON `{ "revision": 0 }` | `WorldFolderResponse` |
| DELETE | `/api/v1/worlds/{worldId}/dreams/{dreamId}` | 쿼리 `revision` 필수 | `WorldFolderResponse` |

201 생성 이외의 성공 상태는 200이다. 경로 ID는 양수, revision은 0 이상이다.
본문 없는 DELETE 요청은 JSON 대신 쿼리로 revision을 전달한다.

`WorldFolderResponse`는 `worldId`, `name`, `selectedDreamCount`, `revision`, `createdAt`, `updatedAt`을 반환한다.
새 폴더는 `selectedDreamCount=0`, `revision=0`이다. 선택 수는 완성 세계관에 반영된 꿈의 수가 아니다.

## revision과 재시도

폴더마다 이름과 선택 목록의 revision을 함께 관리한다. 변경 요청에는 마지막 응답의 revision을 전달한다.
실제 이름 변경·꿈 추가·꿈 제외 시 한 번 증가한다. 이미 선택한 꿈 추가, 선택되지 않은 내 완성 꿈 제외,
같은 이름 재전달은 최신 revision일 때만 성공하며 revision·수정 시각은 유지한다.

오래된 revision은 같은 내용이라도 `409 WORLD_409_1`이다. 네트워크 오류 후 이전 revision으로 재전송해
409가 오면 상세·목록을 다시 조회하고 현재 선택 여부를 확인한다. 서버가 오래된 요청을 임의로 재적용하지 않는다.
같은 이름의 폴더 생성 POST는 별도 폴더를 만든다. 생성 응답 유실 시 목록을 확인한다.

모든 쓰기는 기존 꿈 API와 같은 **사용자 행 잠금 → 폴더·꿈 검사 → 저장** 순서다.
폴더 편집·꿈 영구 삭제가 동시에 실행돼도 선택 관계·수·revision이 함께 커밋 또는 롤백된다.
DB의 `UNIQUE(world_id,dream_id)`와 폴더 `@Version`도 유지한다.

## 페이지 조회

두 목록 모두 `size`는 기본 20, 최대 50이다. 이는 **조회 한 페이지의 크기**이며 선택 개수 제한이 아니다.
전체 목록을 가져오거나 전체 count를 실행하지 않고 `size+1`행으로 `hasNext`를 계산한다.

- 폴더: ID 내림차순. `items`, `hasNext`, `nextBeforeId`를 반환한다.
  다음 요청의 `beforeId`에 `nextBeforeId`를 전달한다. 마지막 페이지는 `nextBeforeId=null`이다.
  새 폴더는 새로고침 시 표시한다. 이름 수정이 목록의 순서를 바꾸지 않는다.
- 선택한 꿈: `worldId`, `revision`, `selectedDreamCount`, `items`, `hasNext`, `nextCursor`를 반환한다.
  항목은 `dreamId`, `dreamedAt`, nullable `title`, `originalText`다.
  다음 요청의 `cursor`에 `nextCursor`를 그대로 전달한다. 마지막 페이지는 `nextCursor=null`이다.
  커서는 같은 폴더와 revision에만 사용할 수 있다. 다른 폴더 커서는 400, 편집 후 이전 커서는 409다.
  409이면 기존 페이지를 비우고 cursor 없이 처음부터 조회한다. 원본 제목·원문 수정은 폴더 revision을 바꾸지 않으므로
  이미 표시한 텍스트는 필요할 때 새로고침한다.

선택 목록의 한 요청은 REPEATABLE_READ 트랜잭션으로 폴더 메타데이터와 페이지의 일관성을 유지한다.
폴더 목록은 1개 조회, 선택 목록은 메타데이터+페이지 2개 조회를 사용한다. 감정 컬렉션·AI 결과를 행별로 조회하지 않는다.
`world_selections(world_id,dreamed_at,dream_id)` 인덱스로 날짜 경계를 조회한다.

## 제외·영구 삭제

- 폴더에서 꿈 제외: 선택 관계만 제거한다. 원본 꿈·개별 AI 결과·다른 폴더는 보존한다.
- 폴더 삭제: 해당 폴더의 선택 관계를 먼저 제거하고 폴더를 삭제한다. 공유 브리지·AI 결과를 삭제하지 않는다.
- 꿈 영구 삭제: 기존 꿈 revision·생성 보호 검사를 통과한 후 연결된 모든 선택 관계를 같은 트랜잭션에서 제거한다.
  관련 폴더의 선택 수는 1 감소하고 revision은 1 증가한다. 폴더가 비어도 남기고 다른 폴더·꿈은 보존한다.
  선택 관계를 정리한 뒤 AI 결과·파일 정리 요청 등을 처리하며 후속 실패 시 선택 변경까지 전체 롤백한다.

완성 세계관 구성 참조와 전체 이미지는 이 PR에서 추가하지 않는다. 해당 테이블이 생기면
현재 꿈 삭제 흐름 안에서 별도로 구성 참조를 정리해야 한다. [꿈 삭제 계약](dream-deletion.md)을 참고한다.

## 오류

| 상태 | 코드 | 의미 |
| --- | --- | --- |
| 400 | WORLD_400_1 | 이름 null·빈 값·Unicode 공백만·100 코드포인트 초과 |
| 400 | WORLD_400_2 | size 1~50 범위 위반, beforeId 양수 위반 |
| 400 | WORLD_400_3 | 커서 형식·폴더 불일치 |
| 400 | COMMON_400_2 | 필수 revision·경로 양수 등 검증 실패 |
| 400 | COMMON_400_1 | JSON·쿼리 파싱 실패, 필수 쿼리 누락 |
| 401 / 403 | 기존 인증·권한 코드 | 인증 누락·만료 / 온보딩 미완료 |
| 404 | WORLD_404_1 / DREAM_404_1 | 폴더 / 꿈 없음 또는 다른 소유자 |
| 409 | WORLD_409_1 | 변경 요청 또는 페이지 커서의 revision이 최신이 아님 |
| 409 | WORLD_409_2 | 꿈이 DRAFT 또는 EMOTION_PENDING |

## DB 적용·검증

신규 DB는 `db/schema-initial.sql`, 기존 DB는 백업·앱 중지 후
`db/migrations/20261025-world-folders.sql`을 수동 적용한다. 자동 실행하거나 운영 DB에 임의 적용하지 않는다.
기존 회원·꿈·AI 결과의 데이터 이동은 없다. DB·연결 문자 집합은 utf8mb4로 설정한다.
이름 저장 컬럼은 H2의 UTF-16 길이 계산까지 수용하도록 VARCHAR(200)으로 두지만 API·도메인 제한은 100 코드포인트다.

통합 테스트는 H2 MySQL 모드에서 권한·Unicode 경계·60개 선택·페이지 쿼리 수·동시 수정·꿈 삭제·롤백을 검증한다.
초기 SQL을 Hibernate validate로 검증하고 수동 마이그레이션 재실행도 확인한다.
실제 MySQL/RDS 실행 및 잠금·실행 계획 점검은 운영 환경 연결 시 필요하다.
