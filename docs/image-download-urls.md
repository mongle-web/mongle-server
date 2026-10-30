# 꿈 이미지 임시 URL 일괄 발급 (#113)

`POST /api/v1/images/download-urls`는 캘린더·보관함에서 여러 꿈 이미지의 원본 파일 URL을 한 번에 받는 API다.
작은 썸네일 파일을 새로 만드는 기능은 아니다. 기존 단건 `GET /api/v1/images/{imageId}/download-url`도 유지한다.
세계관 이미지 ID와 API는 별도이며 이 배치 요청에 섞지 않는다.

## 요청

온보딩을 마친 회원의 Bearer Access Token을 사용한다. 소유자는 JWT subject로 결정한다.

```http
POST /api/v1/images/download-urls
Authorization: Bearer {accessToken}
Content-Type: application/json

{"imageIds":[101,102,101,103]}
```

- `imageIds`는 **중복 제거 전 1~31개**다. 배열 생략·null·빈 배열·32개 이상, null·0·음수 원소는 400 `COMMON_400_2`다.
- 중복 ID는 한 번만 처리하고 처음 나온 순서로 반환한다. 위 요청은 101·102·103 순서의 3개 항목이 된다.
- 미로그인 401, 온보딩 미완료 403은 기존 인증 정책을 따른다.
- 인스턴스 동시 배치 한도 초과는 전체 503 `IMAGE_503_2`다. 잠시 후 재시도하고 한도 초과를 피하려고 단건 요청으로 동시에 풀어 보내지 않는다.

## 응답

정상 접수한 배치는 일부·모든 항목이 실패해도 HTTP 200이며 `Cache-Control: no-store`다.
최상위 `success=true`만으로 모든 URL이 발급됐다고 판단하지 않고 **각 항목의 `status`와 `failureCode`**를 확인한다.
아래는 응답 형식을 설명하는 가상 데이터다.

```json
{
  "success":true,
  "code":"SUCCESS",
  "message":"요청에 성공했습니다.",
  "data":{
    "items":[
      {"imageId":101,"imageVersion":4,"status":"SUCCESS","url":"https://images.example.test/a?signature=example","expiresAt":"2026-10-30T13:05:00Z","failureCode":null},
      {"imageId":102,"imageVersion":null,"status":"FAILED","url":null,"expiresAt":null,"failureCode":"IMAGE_404_1"},
      {"imageId":103,"imageVersion":6,"status":"FAILED","url":null,"expiresAt":null,"failureCode":"IMAGE_502_2"}
    ]
  },
  "errors":[]
}
```

| 필드 | 의미 |
|---|---|
| `imageId` | 요청한 ID |
| `imageVersion` | URL에 사용한 DB 스냅샷의 이미지 버전. 없는 결과는 null |
| `status` | `SUCCESS` 또는 `FAILED` |
| `url`, `expiresAt` | 성공 시 HTTPS URL과 만료 시각. 실패 시 둘 다 null |
| `failureCode` | 성공 시 null, 실패 시 아래 코드 |

| 실패 코드 | 처리 |
|---|---|
| `IMAGE_404_1` | 타인·없는 이미지·삭제된 꿈/이미지·연결된 꿈 없음·성공 파일 없음을 구분하지 않는다. `imageVersion`도 null이다. |
| `IMAGE_503_1` | 본인 성공 파일은 있으나 저장소가 비활성/미설정이다. |
| `IMAGE_502_2` | 해당 파일의 서명 호출 실패 또는 잘못된 HTTPS/만료 응답이다. 비공개 키·SDK 오류 메시지는 반환하지 않는다. |

`imageVersion`은 이미지 행의 현재 버전이다. 재생성 시작·실패로 버전이 증가해도 이전 성공 파일을 표시할 수 있다.
저장된 성공 파일이 있으면 `PROCESSING`·`FAILED` 상태에서도 URL을 발급한다. 성공 파일이 한 번도 없었다면 발급하지 않는다.
새 재생성이 성공하면 다음 요청은 새 파일과 새 버전을 반환한다. 과거 버전 파일을 지정해 받는 API는 아니다.

## 캘린더·보관함 연동

1. 기록 날짜·기록 일수는 기존 `GET /api/v1/archives/calendar?month=YYYY-MM`으로 표시한다.
2. `GET /api/v1/archives?month=YYYY-MM`에서 해당 월의 카드를 받고, 응답의 다음 페이지가 있으면 기존 커서로 이어서 조회한다.
3. 카드의 `image.hasResult=true`인 `image.imageId`를 모아 요청당 최대 31개씩 일괄 URL을 발급한다.
4. `data.items`를 **imageId로 매칭**해 이미지에 적용한다. 중복은 제거되므로 요청 배열 길이와 응답 길이가 다를 수 있다.
5. 화면 내 메모리에서 `imageId`·`imageVersion`·`url`·`expiresAt`을 함께 관리한다. 응답 버전이 기존 목록과 달라졌다면 최신 이미지 상태/목록을 확인해 표시를 갱신한다.
6. URL은 **최대 5분**이며 반환된 `expiresAt`을 따른다. 만료가 가까워지면 현재 화면에 필요한 항목만 모아 다시 발급한다.
7. 새 이미지 생성 완료·꿈 삭제 후에는 목록과 해당 URL 정보를 갱신한다. 재생성 중·실패 시 이전 성공 파일 표시는 유지한다.
8. 항목 실패 시 해당 칸만 기본 이미지로 표시하고 정상 항목은 그대로 사용한다. 재시도는 필요한 항목에만 제한한다.

발급 후 삭제·재생성이 일어나면 조회 스냅샷과 현재 상태가 달라질 수 있다. 이미 발급한 URL은 만료 전까지 유효할 수 있고,
파일 정리로 먼저 사용할 수 없게 될 수도 있다. API 응답의 소유권 검증은 URL 발급 시 조회 스냅샷 기준이다.
서명 URL을 영구 저장하거나 오류 로그·분석 도구·공유 메시지에 넣지 않는다.

## 구현·운영

- 소유자·연결된 원본 꿈·성공 파일·버전을 **프로젝션 쿼리 한 번**으로 조회한다. N개 URL에 N개 DB 조회를 실행하지 않는다.
- `ImageTransactions.assets()`의 읽기 트랜잭션이 끝난 후 서명한다. 호출자의 트랜잭션도 서명 중에는 일시 중단한다.
- 추가 작업 스레드·무제한 큐를 만들지 않으며 배치 안에서는 순서대로 서명한다.
- 인스턴스 동시 배치 수는 기존 `mongle.image.execution.max-concurrent-calls` 값(기본 4)을 사용한다.
  이미지 생성 슬롯과 별도 세마포어이므로 읽기 요청이 진행 중인 생성 슬롯을 소모하지 않는다.
- 단건·일괄 발급은 동일한 HTTPS·사용자 정보 없는 URL·현재 이후 최대 5분 만료 검증을 공유한다.
- AI 호출·크레딧 예약/차감·파일 업로드/삭제·DB 데이터 변경을 하지 않는다. S3 객체 존재 여부를 매번 HEAD로 확인하지 않는다.
- 새 테이블·마이그레이션·환경변수·IAM 권한은 추가하지 않는다. 성공 URL 발급에는 기존 S3 연결·읽기 권한이 준비되어 있어야 한다.
- 코드 머지·CI/CD 성공 후 운영 Swagger에 `POST /api/v1/images/download-urls`가 나타난 것을 확인해 운영 연동을 시작한다.

## 검증

`ImageDownloadServiceTest`는 중복·순서·부분 실패·HTTPS/만료 검증·미설정 저장소·동시 한도/반납과 민감 정보 비노출을 검증한다.
`ImageDownloadIntegrationTest`는 실제 HTTP 인증·요청 검증·응답 필드·no-store·기존 단건 호환성과 Swagger 스키마를 검증한다.
공통 `ImageDownloadContract`를 H2와 임시 MySQL 8.4에서 실행해 31개 배치의 SQL 1회, 소유권·삭제·연결 끊긴 데이터,
이전 성공 파일 보존·교체, 조회 중 과금/파일 쓰기 없음, 트랜잭션 중단과 별도 연결 삭제 완료를 확인한다.
