# 로컬 Swagger 확인

서버 저장소에서 Java 21로 실행한다.

```sh
./gradlew bootRun --args='--spring.profiles.active=local'
```

서버 시작 완료 후 브라우저에서 다음 주소를 연다.

- 화면: http://localhost:8080/swagger-ui.html (또는 /swagger-ui/index.html)
- OpenAPI JSON: http://localhost:8080/v3/api-docs

IntelliJ에서는 MongleBackendApplication 실행 설정의 Active profiles를 local로 두고 실행하면 된다. 문서 열람에는 로그인이나 페이앱/OpenAI 키가 필요 없다. 업무 API를 Try it out으로 호출하려면 실제 로컬 로그인에서 발급한 access token을 Authorize에 입력한다. 로컬 토큰 발급 우회 API는 제공하지 않는다.

local은 메모리 H2와 create-drop 설정이므로 서버 종료 시 데이터가 사라진다. RDS 검증 환경과 혼용하지 않는다. 별도 포트를 지정했다면 주소의8080도 그 포트로 바꾼다. 접속 거부는 서버 실행/포트 점유를,404는 실행 브랜치와 local 프로필을 확인한다. 공통·운영 프로필은 Swagger 기본 비활성이다.

이미지·결제 경로가 Swagger에 존재해도 실제 생성·결제가 활성화됐다는 뜻은 아니다. #64의 개별 이미지 신규 생성은 Provider/S3/옵션과 크레딧 활성·확정 사용량·충분한 잔액이 함께 있어야 한다. 기존 결과 재사용은 추가 과금하지 않는다. 생성 후 잔액·원장 API를 다시 조회해 예약/확정/해제를 화면에 반영한다.

#66 현재 구현본은45경로55개 API다. 세계관 생성·조회·페이지·재시도·부분 반영·삭제 영향 조회6개를 추가했다. 전체 MVP 최종 명세는 세계관 전체 이미지·종합 해석 등 남은 기능 구현 후 갱신한다.
