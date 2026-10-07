# 도메인 초기 세팅

기준 자료는 [mongleERD](https://www.erdcloud.com/d/kTau4h255aRWuyfst)다.
2026-10-01 ERDCloud의 MySQL SQL 미리보기에서 필드·NULL 허용·PK·비식별 관계까지 확인했다.

대화의 최종 합의에 따라 S0/S1에 필요한 엔티티 6개, 최소 Repository,
JPA Auditing, 공통 Enum, Swagger 인프라와 Controller/Api 작성 규칙을 추가한다.
공통 응답과 예외 처리에는 기존 구현을 사용한다.
회원가입·로그인, 꿈 CRUD, AI 호출, 서사화 API 등은 각각의 기능 PR에서 구현한다.

## 모델과 스키마

| 모델 | 테이블 | 핵심 필드와 관계 |
| --- | --- | --- |
| User | users | email(255), nickname(100), 생성·수정 시각 |
| Dream | dreams | 사용자, original_text(TEXT), dreamed_at(선택), representative_emotion(선택), analysis_status |
| DreamScene | dream_scenes | 꿈, sequence_no, content(TEXT), is_disconnected_from_previous |
| DreamEntity | dream_entities | 꿈, entity_type, name(255), description(선택) |
| DreamSceneEntity | dream_scene_entities | (dream_scene_id, dream_entity_id) 복합 PK와 FK, 별도 시각 필드 없음 |
| AiGenerationLog | ai_generation_logs | 사용자, 작업·모델·프롬프트 버전, 토큰 수, 실제·기준 비용, 지연 시간, 성공 여부, 생성 시각 |

`User`, `Dream`, `DreamScene`, `DreamEntity`는 생성·수정 시각을 갖는 `BaseEntity`를 상속한다.
`AiGenerationLog`는 ERD에 `updated_at`이 없으므로 `BaseCreatedEntity`만 상속한다.
Auditing 시각은 UTC `LocalDateTime`으로 저장하고, `dreamed_at`은 사용자가 입력하는 `LocalDate`로 둔다.
상태·유형은 `EnumType.STRING` 및 VARCHAR로 저장한다(MySQL 네이티브 ENUM을 사용하지 않는다).
비용은 `BigDecimal`이다. 이후 호출 로그 이슈에서 추정 비용을 nullable `DECIMAL(20,8)`로
확장하고, 비교 비용은 기존 `DECIMAL(12,8)`을 유지했다. 새 로그의 미확정 비용/수량은 null로 저장한다.
시도 추적·단가 스냅샷·기존 DB 마이그레이션은 [로그와 비용 문서](ai-generation-logging.md)를 참고한다.

ERD에는 꿈 제목·음성 파일 URL·비밀번호가 없어 추가하지 않았다.
`dreamed_at`의 NULL 허용과 나머지 VARCHAR 길이, 기본값도 설계에 맞췄다.
ERD의 BIGINT PK에 ID 생성 방식은 없으므로 단일 PK에는 `IDENTITY`/`AUTO_INCREMENT`를 사용했다.
`ai_generation_logs.user_id`에는 ERD SQL에서 FK가 빠져 있지만 사용자 참조이므로 FK를 추가했다.
이 두 가지는 초기 세팅에서 명시한 구현 선택이다. 이메일 UNIQUE 등 추가 제약은 인증 설계를 확정할 때 정한다.

## 엔티티 생성 규칙

엔티티는 외부에서 공개 정적 팩터리 메서드로만 생성한다.
`User`, `Dream`, `DreamScene`, `DreamEntity`, `AiGenerationLog`는 `create(...)`,
장면과 요소의 연결인 `DreamSceneEntity`는 `link(scene, entity)`를 사용한다.
각 팩터리는 엔티티 내부의 `@Builder(access = AccessLevel.PRIVATE)`를 호출한다.
필수값 검증은 private 생성자에 두며, JPA용 기본 생성자는 protected로 유지한다.
ID·Auditing 시각·꿈의 초기 분석 상태는 팩터리 입력으로 받지 않는다.

서비스에서 요청 DTO의 값과 조회한 연관 엔티티를 팩터리에 전달한 뒤 Repository로 저장한다.
엔티티에는 요청 DTO 의존성을 두지 않는다. 아래는 서비스에서 사용할 생성 예시다.

```java
User user = User.create("test@example.com", "몽글");
Dream dream = Dream.create(user, "도서관에서 고양이를 만났다.", LocalDate.of(2026, 10, 1), "호기심");
DreamScene scene = DreamScene.create(dream, 1, "도서관에 들어갔다.", false);
```

선택 필드는 없으면 null을 전달한다. 연결 생성 시에는 아래 저장 순서를 따른다.

## 연관관계와 Repository

모든 `ManyToOne`은 단방향 `LAZY`다. 초기 단계에서 양방향 컬렉션, cascade, orphanRemoval을 설정하지 않는다.
따라서 부모 저장이 자식을 자동 저장하지 않으며, 부모 삭제가 자식이나 AI 로그를 자동 삭제하지 않는다.
기능 PR에서 실제 저장·재분석·삭제 흐름과 트랜잭션 경계를 정하고 필요한 Repository와 쿼리를 추가한다.

초기 Repository는 독립적인 루트인 `User`, `Dream`, `AiGenerationLog` 3개다.
장면·꿈 요소·연결의 Repository는 실제 조회·저장 방식이 정해질 때 추가한다.
이번 영속성 검증은 EntityManager로 자식 모델을 저장한다.

`DreamSceneEntity`는 `EmbeddedId` + `MapsId`를 사용한다.
장면과 요소를 저장한 뒤 `DreamSceneEntity.link(scene, entity)`로 연결한다.
같은 장면–요소 쌍은 DB 복합키가 중복을 차단한다.
팩터리에서 다른 꿈의 장면과 요소를 연결하는 것도 차단한다.
DB의 두 FK 자체는 같은 꿈 여부를 검증하지 않으므로 이후 API 역시 이 팩터리와 소유권 검증을 사용해야 한다.

S1 서사화는 `DreamStory` (`dream_stories`)로 구현하며 상세 계약은 [꿈 서사화](dream-story.md)를 따른다.
S2 이미지 생성에서 `DreamArtwork`,
S3 세계관에서 `DreamWorld`, `DreamWorldDream`, `WorldEntity`, `WorldEntityMapping`,
`DreamConnection`, `BridgeNarrative`를 추가한다.

## Swagger 작성 규칙

`domain/{도메인}/controller/api/XxxApi`는 `Tag`, `Operation`, Swagger 응답·파라미터 설명 등
문서화 어노테이션과 메서드 계약을 담당한다. 구현 Controller는 해당 인터페이스를 구현한다.
HTTP 매핑, Spring의 `RequestBody`·`PathVariable`, `Valid`와 서비스 호출은 Controller에 둔다.
DTO의 입력 제약은 요청 record/class에 둔다.

아래는 다음 기능 PR에서 작성할 패턴 예시이며, 현재 앱에는 이 API를 등록하지 않았다.

```java
// domain/dream/controller/api/DreamApi.java
@Tag(name = "Dream", description = "꿈 기록 API")
public interface DreamApi {
    @Operation(summary = "꿈 기록 생성")
    @SecurityRequirement(name = SwaggerConfig.BEARER_AUTH)
    ResponseEntity<ApiResponse<DreamCreateResponse>> createDream(DreamCreateRequest request);
}

// domain/dream/controller/DreamController.java
@RestController
@RequestMapping("/api/v1/dreams")
@RequiredArgsConstructor
public class DreamController implements DreamApi {
    private final DreamService dreamService;

    @Override
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<DreamCreateResponse>> createDream(
            @Valid @RequestBody DreamCreateRequest request) {
        DreamCreateResponse result = dreamService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }
}
```

위 Swagger의 `SecurityRequirement`는 인증이 필요한 메서드에만 붙인다.
회원가입·로그인처럼 공개 API에는 붙이지 않는다. JWT 보안 Scheme 자체는 인증 필터를 구현하지 않는다.
실제 API 추가 시 성공·실패 HTTP 상태와 기존 `ApiResponse` 형식까지 문서화한다.
JSON 응답 API는 매핑에 `produces = MediaType.APPLICATION_JSON_VALUE`를 지정해 응답 미디어 타입을 명확히 한다.

## 검증 범위

- 꿈·장면·꿈 요소·연결을 실제 저장 후 조회하며 LAZY, 상태 문자열, 복합키와 Auditing을 확인한다.
- AI 비용 DECIMAL의 저장·조회와 캐시 토큰 수를 확인한다.
- 다른 꿈의 요소 연결과 복합키 중복을 차단하는지 확인한다.
- 별도 H2 MySQL 모드 DB에 ERD 기반 초기 SQL을 적용하고 `ddl-auto=validate`로 매핑을 확인한다.
- 실제 HTTP로 Swagger UI와 `/v3/api-docs`를 읽고, 테스트 전용 Controller가 Api 인터페이스의 문서화를 상속하는지 확인한다.

H2 MySQL 모드 검증은 외부 MySQL 서버 연결 검증과 별개다.
외부 DB 접속 정보가 제공되면 해당 서버에 스키마를 준비하고 `mysql` 프로필로 별도 검증한다.
