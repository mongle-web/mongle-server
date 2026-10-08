# mongle-server
몽글 서버입니다.
---

# 📂 프로젝트 구조

```text
com.mongle.backend
├── domain
│   ├── user
│   │   ├── entity           
│   │   └── repository       
│   ├── dream
│   │   ├── entity           
│   │   └── repository       
│   ├── ai
│   │   ├── entity           
│   │   ├── repository
│   │   ├── gateway          # AiGateway 호출 인터페이스
│   │   ├── dto
│   │   │   ├── request      # 호출 맥락·메시지·출력 스키마
│   │   │   ├── response     # 생성 내용·사용량·종료 사유
│   │   │   └── logging      # 시도 메타데이터·비용 계산 결과
│   │   ├── config           # 모델별 가격표·비동기 실행 자원·한도
│   │   ├── service          # 비용 계산·독립 트랜잭션 로그 저장
│   │   ├── error            # AI 호출 오류 코드·예외
│   │   └── liner            # 실제 Gateway 구현·설정·HTTP 통신·JSON 변환
│   └── world               
└── global
    ├── common              # BaseCreatedEntity, BaseEntity, GenerationStatus
    ├── config              # JpaConfig, SwaggerConfig
    ├── error               # 기존 공통 예외 처리
    ├── logging             # HTTP 요청 추적·커밋 후 성공 로그
    └── response            # 기존 ApiResponse
```

| Package     | Description   |
|-------------|---------------|
| `domain`    | 도메인별 비즈니스 로직  |
| `global`    | 공통 설정 및 예외 처리 |
| `resources` | 설정 파일         |
| `test`      | 테스트 코드        |

기능 개발 시 각 도메인에 `controller/api`, `service`, `dto/request`,
`dto/response`를 추가한다. 구현체가 하나인 Service는 class로 시작한다.

## 실행과 DB 설정

Java 21을 사용한다. 프로필 미지정 시 `local`이 적용된다.

```sh
./gradlew bootRun
./gradlew test
```

- `local`: H2 메모리 DB(MySQL 모드), `ddl-auto=create-drop`. 재시작하면 데이터가 사라진다.
- `test`: 별도 H2 메모리 DB. 테스트 클래스에서 명시적으로 활성화한다.
- `mysql`: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경변수로 외부 MySQL에 연결한다.
  공통 설정의 `ddl-auto=validate`를 사용하며 테이블을 자동 변경하지 않는다.

외부 DB를 새로 준비할 때는 `src/main/resources/db/schema-initial.sql`을 먼저 적용한다.
이 SQL은 앱에서 자동 실행되지 않으며, 기존 테이블이 있는 DB에 재적용하지 않는다.
셸에 다음 환경변수를 설정한 뒤 실행한다(`.env` 파일은 자동으로 로드되지 않는다).

```sh
export DB_URL='jdbc:mysql://localhost:3306/mongle'
export DB_USERNAME='mongle'
export DB_PASSWORD='your-local-password'
./gradlew bootRun --args='--spring.profiles.active=mysql'
```

Swagger UI는 `local`에서 [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html),
OpenAPI JSON은 [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)로 확인한다.
외부 DB 프로필에서는 문서 엔드포인트가 기본 비활성화되어 있다.
필요한 개발 환경에서 `SPRINGDOC_API_DOCS_ENABLED=true`, `SPRINGDOC_SWAGGER_UI_ENABLED=true`로 활성화한다.
JWT Scheme은 문서화 설정이며 실제 인증 구현은 S0 기능 개발에서 추가한다.

H2 콘솔은 `local`에서 [http://localhost:8080/h2-console](http://localhost:8080/h2-console)로 접속한다.
Driver Class는 `org.h2.Driver`, JDBC URL은
`jdbc:h2:mem:mongle;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE`,
사용자는 `sa`, 비밀번호는 비워둔다. Gradle 의존성을 새로고침하고 서버를 재시작해야 콘솔 모듈이 적용된다.

초기 모델의 기준, 연관관계와 Swagger 작성 예시는 [도메인 초기 세팅 문서](docs/domain-setup.md)를 참고한다.
LLM Gateway의 요청·응답·오류 계약과 테스트용 구현 사용법은 [Gateway 계약 문서](docs/ai-gateway-contract.md)를 참고한다.
시도별 로그·토큰 사용량·비용 계산과 기존 MySQL DB 변경 방법은 [로그와 비용 문서](docs/ai-generation-logging.md)를 참고한다.
실제 LINER 구현의 환경변수, 호출 흐름, 타임아웃·재시도 정책은 [LINER 연동 문서](docs/liner-gateway.md)를 참고한다.
`LINER_API_KEY`를 실행 환경에 설정하면 도메인 서비스에서 `AiGateway`를 주입해 사용할 수 있다.
키가 없어도 서버는 기동하며 신규 꿈 분석·서사화 생성은 503으로 거절한다.
Gateway는 `CompletableFuture<AiGenerationResult>`를 반환한다. 호출부는 완료 처리를 연결해 사용한다.
[비동기 Gateway 문서](docs/ai-gateway-async.md)에 실행 흐름·환경변수·호출 제한·취소 정책을 정리했다.
[운영 로깅 문서](docs/operational-logging.md)에 HTTP·AI·인증 로그의 추적 ID, 레벨과 조회 방법을 정리했다.
실제 장면 분석·서사화의 호출 흐름과 동시 처리 제한은
[꿈 AI 연동 문서](docs/dream-ai-gateway.md)를 참고한다.

---

---

# 📐 Convention

### 🌿 Branch Strategy

| Branch              | Description        |
|---------------------|--------------------|
| `main`              | 배포 브랜치             |
| `develop`           | 개발 브랜치             |
| `feat/이슈번호-설명`     | 기능 개발              |
| `fix/이슈번호-설명`      | 버그 수정              |
| `refactor/이슈번호-설명` | 리팩토링               |
| `chore/이슈번호-설명`    | 설정 및 기타 작업         |
| `docs/이슈번호-설명`     | README, 문서 및 주석 수정 |
| `hotfix/이슈번호-설명`   | 긴급 수정              |

---

### 💬 Commit Convention

| Type       | Description |
|------------|-------------|
| `feat`     | 새로운 기능 추가   |
| `fix`      | 버그 수정       |
| `refactor` | 리팩토링        |
| `docs`     | 문서 수정       |
| `chore`    | 설정 및 기타 작업  |
| `init`     | 프로젝트 초기 설정  |

---

### 🔀 Pull Request

- Base Branch : `develop`
- Reviewer 1명 이상 지정
- AI Code Review 확인 및 반영
- 팀원 1명 이상의 Approve 후 Merge
- PR 제목은 `[Type] 구현 내용` 형식을 사용

---
