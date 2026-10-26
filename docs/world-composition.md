# 세계관 완성본·부분 반영 (#66)

## 범위

폴더의 선택 목록과 실제 완성본을 분리한다. 완성본은 날짜순의 고정 성공 서사 버전과 인접한 두 서사의 성공 연결을 참조하며 본문을 중복 복제하지 않는다. 폴더마다 최신 완성본 1개를 유지하고 생성·부분 반영 실패 시 이전 완성본 전체를 보존한다. 세계관 서사 생성·반영·연결에는 크레딧을 차감하지 않는다.

세계관 전체 이미지 API·과금은 후속 기능이다. 이 작업이 개별 이미지나 미래의 전체 이미지를 자동 재생성·삭제하지 않는다. 과거 완성본 이력 API는 제공하지 않는다.

## HTTP 계약

온보딩을 완료한 Bearer JWT와 Cache-Control: no-store를 사용한다. 회원 ID를 입력받지 않는다.

| 메서드 | /api/v1 기준 경로 | 요청과 동작 |
| --- | --- | --- |
| POST | /worlds/{worldId}/composition | folderRevision·compositionRevision. 작업 접수202 |
| GET | /worlds/{worldId}/composition | 완성본·작업 상태 및 변경 안내 |
| GET | /worlds/{worldId}/composition/entries | size1~50·기본20, 선택 cursor. 완성된 구성만 페이지 조회 |
| POST | /worlds/{worldId}/composition/retry | 실패·만료 작업의 최신 jobVersion. 재접수202 |
| POST | /worlds/{worldId}/composition/dreams/{dreamId}/apply | folderRevision·compositionRevision·storyVersionId. 부분 반영202, 동일 버전은200 |
| GET | /dreams/{dreamId}/world-impacts | size1~50·기본20, 선택 beforeId. 영구 삭제 영향 목록 |

최초 compositionRevision은0이다. 폴더 revision은 이름·선택 목록 편집 값, compositionRevision은 완성본 교체·삭제 영향 값, jobVersion은 진행 상태·재시도 충돌 값이다. 서로 바꿔 전달하지 않는다.

POST는 외부 생성 완료를 기다리지 않고 영속 작업 접수 후 반환한다. 상태를 조회해 완료를 확인한다. 같은 진행 입력은 기존 attemptId를 반환한다. 이미 완료한 요청을 옛 revision으로 재전송하면409이므로 다시 조회한다. 재접속·단순 조회로 새 AI 요청을 만들지 않는다.

GET 상태의 attemptId=null은 아직 요청이 없음을 뜻한다. PENDING/PROCESSING 중이거나 FAILED여도 hasResult=true이면 이전 완성본을 표시할 수 있다. selectionChanged·sourceChanged·newStoryAvailable·hasConnectionGaps는 완성본 기준의 안내 플래그다. 모든 본문 페이지를 불러오지 않아도 안내를 표시할 수 있다.

구성 페이지는 꿈 날짜순이다. 서사 sections와 그 앞의 bridgeBeforeId/bridgeBefore, connectionGap·sourceChanged·newStoryAvailable을 반환한다. nextCursor를 그대로 사용한다. 완성본 revision이 달라지면409 후 처음부터 조회한다. 첫 번째로 남은 꿈 앞은 빈 연결 구간으로 표시하지 않는다.

## 생성과 부분 반영

새 전체 생성은 선택한 꿈2개 이상과 현재 원문·감정에 대응하는 성공 서사가 필요하다. 서사 버전을 날짜순으로 고정하고 연결에는 앞뒤 두 성공 서사만 전달한다. 같은 사용자·두 버전·프롬프트·설정의 성공 연결은 다른 폴더에서도 재사용한다.

모든 구성 요소가 성공하면 후보를 한 번에 공개한다. 실패·만료·중단은 이전 완성본을 바꾸지 않는다. 원문 수정이나 새 성공 서사 생성만으로 기존 완성본의 문장을 바꾸지 않는다.

부분 반영의 대상은 실제 완성본 안의 꿈이다. 현재 선택 목록에서 제외했더라도 반영할 수 있다. 다른 버전을 요청한다면 해당 꿈의 현재 성공 버전이어야 한다. 대상 서사와 앞뒤 연결만 갱신하고, 나머지 서사·연결·기존 빈 구간은 유지한다. 다른 꿈에 더 새로운 서사가 있어도 함께 반영하지 않는다. 같은 고정 버전은 무료 조회와 같은 변경 없는 응답이다.

명시적 재시도는 고정 입력과 이미 성공한 후보 연결을 재사용한다. 실패·만료 연결만 기존 브리지 계약으로 다시 시도한다. 폴더·구성·대상 출처·생성 설정이 바뀌었으면 새 생성/부분 반영 요청이 필요하다.

## 실행과 경합

후보 구성이 사용하는 꿈은 작업 사이에도 WORLD_COMPOSITION 사유로 수정·삭제 및 새 분석/서사 작업을 막는다. 다른 꿈의 변경은 막지 않는다. 기존 성공 결과 조회는 가능하다. 같은 사용자 행 잠금과 revision으로 꿈 삭제·폴더 편집·부분 반영·최종 교체를 직렬화한다.

MONGLE_WORLD_COMPOSITION_ENABLED는 신규 생성/재시도를 제어한다. MONGLE_WORLD_COMPOSITION_WORKER_ENABLED는 인스턴스별 워커를 제어하므로 접수한 작업을 처리할 워커가 최소1개 필요하다. 신규 기능을 비활성화해도 이미 접수한 작업과 조회는 유지한다.

기본 poll2초·배치20·동시 연결2개이며 각각 MONGLE_WORLD_COMPOSITION_POLL_INTERVAL(100ms~1분), BATCH_SIZE(1~100), MAX_CONCURRENT_CALLS(1~8)로 조절한다. 앞의 환경변수와 같은 MONGLE_WORLD_COMPOSITION_ 접두사를 사용한다. 기존 AI 실행 자원의 공통 한도도 적용한다. DB 트랜잭션·사용자 잠금을 외부 IO 동안 유지하지 않는다.

단계별 claim은120초, 작업 lease는 진행 시 갱신되는180초다. claim을 잃으면 WORKER_INTERRUPTED, 작업 만료는 ATTEMPT_EXPIRED로 실패 처리하고 수정 보호를 해제한다. 영속 상태를 재확인하며 불확실한 외부 호출을 자동 재전송하지 않는다. 입력·설정·revision 변경은 INPUT_CHANGED, 연결 실패는 BRIDGE_FAILED다. 클라이언트가 없더라도 워커가 제한된 배치로 복구한다.

## 영구 삭제

영향 조회는 선택 관계만 있는 폴더와 실제 완성본에서 사용하는 세계관을 구분한다. 선택 목록에서 제외한 꿈도 usedInComposition=true이면 포함한다. 조회는 안내 시점 스냅샷이며 실제 DELETE가 보호와 관계를 다시 확인한다.

B 삭제 시 B 구성과 AB/BC 참조를 먼저 제거한 뒤 브리지·성공 서사·개별 파생 데이터를 삭제한다. 새 AC 연결은 만들지 않고 C 앞에 빈 구간을 표시한다. 폴더·남은 구성은0~1개에서도 유지한다. 완성본 revision이 증가해 옛 페이지 커서는 무효다.

삭제된 출처를 사용하는 후보 작업도 정리하고 늦은 완료로 부활시키지 않는다. 정리 후속 단계가 실패하면 삭제·구성·선택 변경 전체가 롤백된다. 이미 성공 확정한 개별 이미지 비용을 꿈 삭제로 반환하지 않는다.

폴더 삭제는 해당 후보·완성 구성·작업을 제거하고 원본 꿈·개별 AI 결과·공유 연결을 보존한다. 이미 접수된 외부 연결 호출의 취소를 보장하지는 않지만 삭제한 완성본을 복구하지 않는다.

## DB·검증 경계

신규 DB는 schema-initial.sql, 기존 DB는 앱/워커 중지와 백업 후 db/migrations/20261026-world-composition.sql을 수동 적용한다. world_compositions·world_composition_entries 2테이블을 추가한다. 재실행은 지원하지만 기존 잘못된 스키마를 자동 교정하지 않으므로 Hibernate validate를 확인한다.

초기 스키마/JPA·반복 DDL은 H2 MySQL 모드로 검증한다. 실제 MySQL/RDS 적용·실행 계획·잠금과 LINER 출력 품질·프론트 종단 검증은 운영 환경에서 확인한다. 테스트 JVM은 통합 컨텍스트 증가에 따라 최대1GiB를 명시하며 운영 JVM 메모리는 별도 설정이다.
