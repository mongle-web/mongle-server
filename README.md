# mongle-server
몽글 서버입니다.
---

# 📂 프로젝트 구조

```text
확정 후 수정
```

| Package     | Description   |
|-------------|---------------|
| `domain`    | 도메인별 비즈니스 로직  |
| `global`    | 공통 설정 및 예외 처리 |
| `resources` | 설정 파일         |
| `test`      | 테스트 코드        |

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
