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

#84 통합 구현본은 49경로 61개 API다. 종합 해석 조회·첫 진입 생성·명시적 갱신 3개를 추가했다. #77의 세계관 전체 이미지 생성·조회·상태 조회 3개도 포함한다. 카카오 단독 로그인은 기존 Auth 설명에 반영되며 OAuth 시작/Callback은 보안 필터가 처리해 API 개수에는 포함하지 않는다. 종합 해석 생성은 기본 비활성이며 MONGLE_RECENT_INTERPRETATION_ENABLED와 LINER 설정을 확인한다. 연동 흐름은 docs/recent-interpretation.md를 따른다. 로컬 통합 명세와 운영에 실제 배포된 명세는 다를 수 있다.

#111은 반복 장소·인물 조회 GET 경로1개를 추가한다. #110 머지 기반의 구현본은52경로·65개 작업이며, 실제 운영 반영은 해당 PR의 머지·CD 후 확인한다. [반복 요소 명세](recurring-elements.md)를 참고한다.
