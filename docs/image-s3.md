# 비공개 S3 이미지 저장소 (#54)

## 연결 상태

`S3ImageAssetStore`는 기존 `ImageAssetStore`의 업로드·삭제·임시 다운로드 URL 발급을 구현한다.
기준은 미병합 #53의 `refactor/52-image-async-cleanup-integration`이다.
AWS SDK for Java 2.x BOM 2.57.2, Apache 5 HTTP 클라이언트를 사용한다.
실제 AWS 접근이나 버킷/IAM 변경 없이 모의 전송·로컬 HTTP·H2로 검증한다.

기본 `MONGLE_IMAGE_S3_ENABLED=false`이면 기존 unavailable 저장소를 사용하고 S3 클라이언트/자격 증명 공급자를 만들지 않는다.
활성화 시 버킷과 리전이 필요하다. 잘못된 설정은 기동 시 거절한다.
테스트/사용자 정의 `ImageAssetStore`가 있으면 기본 어댑터 생성을 생략한다.
`available()`은 어댑터가 열려 있음을 뜻하며 자격 증명·권한·버킷 접근의 성공 여부를 미리 보장하지 않는다.
기동 시 인증 정보를 해석하거나 버킷 조회로 상태를 점검하지 않는다.

실제 `ImageGenerator`는 여전히 unavailable이다. S3를 켜는 것만으로 신규 이미지 생성 API를 운영할 수 없다.
이미지 생성 Provider와 스타일/분위기 설정은 후속으로 연결한다. 서비스 크레딧 차감도 추가하지 않는다.

## 파일과 URL 계약

- 도메인이 소유권·원본·입력·시도를 검사하고 서버가 만든 `dream-images/{userId}/{imageId}/{UUID}.png|.jpg` 키만 전달한다.
- 어댑터는 전용 키 형태·바이트 크기·MIME/확장자 일치를 검사한다. 실제 PNG/JPEG 디코딩은 기존 `ImagePayload.parse`에서 수행한다.
- PutObject에 콘텐츠 유형과 길이를 전달하며 공개 ACL을 설정하지 않는다. 버킷은 비공개여야 한다.
- DeleteObject의 정상 응답과 명시적 `404/NoSuchKey`만 성공이다. AccessDenied/NoSuchBucket/알 수 없는 404/통신 오류는 실패다.
- 다운로드는 HTTPS GET 서명으로 발급한다. 1~300초의 정수 초 유효기간과 브라우저 실행 가능 여부를 검사한다.
- 응답의 expiresAt은 실제 서명의 만료 시각과 발급 시작+요청 기간 중 빠른 시각이다. 서명에 소요된 시간을 빼서 최대 5분 계약을 넘기지 않는다.
- URL은 API 소유권 검사가 끝난 뒤 발급하며 메타데이터에 저장하지 않는다. URL 응답은 기존처럼 no-store다.
- 임시 자격 증명이 먼저 만료되거나 권한이 철회되면 URL은 expiresAt보다 일찍 사용할 수 없게 될 수 있다.

## 실행과 실패

업로드와 보상 삭제는 `image-result-*`, 정리 요청 삭제는 `image-cleanup-io-*`의 기존 제한된 실행기를 사용한다.
DB 트랜잭션·사용자 행 잠금 밖에서만 저장소를 호출한다. Provider 응답 대기에 사용하는 스레드를 추가하지 않는다.
HTTP 연결 수는 `min(image.execution.max-concurrent-calls, result-threads) + image.cleanup.max-concurrent-calls`로 제한한다.
연결/연결 풀 획득에는 connect-timeout, 소켓에는 attempt-timeout, SDK에는 시도별/전체 시간 제한을 설정한다.
전체 시간 제한은 SDK 재시도 대기를 포함하며 정리 worker lease보다 짧아야 한다.
이미지 Provider 연결 시에는 Provider 대기·결과 큐 대기·업로드·DB 기록·필요한 보상 시간을 이미지의 2분 lease와 함께 검토해야 한다.
시간 제한이 있어도 DB/외부 저장소가 원자적으로 처리되는 것은 아니다.
타임아웃은 S3 서버의 실행 취소를 보장하지 않는다. 업로드 성공 여부가 불명확하거나 보상보다 늦게 서버에 반영된 객체는 운영 대조 회수가 필요할 수 있다.

SDK 재시도 max-attempts는 최초 호출을 포함한 최대 횟수다. 정리 워커의 영속 attempt-count와 별개다.
정리 실패는 기존 RETRY_WAIT/HELD 계약을 따른다. SDK 오류 본문/키/URL을 일반 예외 체인으로 전달하지 않고 고정 메시지로 변환한다.
업로드 실패나 늦은 완료의 미채택 파일은 새 시도 키만 보상 삭제하고, 보상 실패는 DISCARDED_UPLOAD 요청에 남긴다.
참조된 성공 파일·활성 시도 보호·삭제 시작 키 채택 차단과 동일 성공 완료 재전송 보호는 #53 계약을 유지한다.
종료 시 워커/완료 실행기 종료 계약 뒤 SDK 클라이언트·서명기·소유한 기본 자격 증명 공급자를 닫는다.
실행 스레드 인터럽트만으로 블로킹 소켓 읽기가 즉시 끝난다고 보장하지 않는다. 요청 시간 제한과 클라이언트 종료로 남은 IO를 제한한다.

## 설정

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| MONGLE_IMAGE_S3_ENABLED | false | S3 어댑터 선택 |
| MONGLE_IMAGE_S3_BUCKET | 빈 값 | 비공개 일반 버킷 이름 |
| MONGLE_IMAGE_S3_REGION | 빈 값 | 버킷의 AWS 리전 |
| MONGLE_IMAGE_S3_CONNECT_TIMEOUT | 2s | 연결/연결 풀 획득 제한 |
| MONGLE_IMAGE_S3_ATTEMPT_TIMEOUT | 4s | 개별 시도/소켓 제한 |
| MONGLE_IMAGE_S3_TOTAL_TIMEOUT | 10s | 재시도를 포함한 전체 요청 제한 |
| MONGLE_IMAGE_S3_MAX_ATTEMPTS | 2 | 최초 호출 포함 1~3회 |

시간 설정은 100ms~30s이며 `connect <= attempt < total < cleanup.lease`다.
정리 워커 활성화 변수 `MONGLE_IMAGE_CLEANUP_ENABLED`는 별개이며 기본 false를 유지한다.
실제 Provider가 없어도 저장소/워커를 운영 설정으로 켜면 과거 정리 요청이 처리될 수 있으므로 배포 시 두 활성화 값을 구분한다.

자격 증명은 `DefaultCredentialsProvider.builder().build()`의 기본 공급 체인으로 공급한다.
코드/YAML/이슈에 키를 넣지 않는다. 운영에서는 배포 환경의 역할을 사용하고 로컬에서는 별도 AWS 프로필 등 SDK 표준 방식을 사용한다.
역할 위임/웹 자격 증명에 사용하는 STS 모듈을 포함한다. 로컬 IAM Identity Center SSO 프로필을 쓰려면 별도 SSO 모듈/실행 환경 설정을 검토해야 한다.
환경·프로필·역할 공급자의 인증 정보 획득/갱신은 SDK가 처리하며 S3 HTTP 시간 제한만으로 모든 외부 자격 증명 공급자 동작을 보장하지 않는다.
SDK wire/auth DEBUG 로그를 켜거나 서명 URL/키를 출력하지 않는다. 운영 설정에 임의의 SDK endpoint override를 추가하지 않는다.

## 버킷과 권한의 운영 전제

이번 삭제 범위는 **버전 관리가 켜진 적 없는 일반 버킷**이다. 앱은 버킷 버전 설정을 조회하거나 변경하지 않는다.
버전 관리 enabled/suspended 버킷에서는 키만 삭제하면 과거 버전이 남을 수 있다. 현재 DB는 versionId를 저장하지 않는다.
따라서 이 어댑터의 완료 상태를 과거 모든 객체 버전의 영구 삭제로 표현하지 않는다. 버전별 회수/보존 정책은 별도 작업이다.
Object Lock/MFA Delete/복제/수명 주기/과거 객체 이력 회수·공개 공유·세계관 전체 이미지 관리는 이번 범위가 아니다.

운영자는 실제 버킷/리전·비공개 접근·Block Public Access·암호화·권한·버전 관리 상태를 확인한 뒤 활성화한다.
앱은 ACL/버킷 정책을 변경하지 않는다. 최소 객체 권한의 예시는 [권한 문서](iam-image-s3-policy.example.json)다.
`REPLACE_WITH_PRIVATE_IMAGE_BUCKET`을 실제 이름으로 교체해 해당 실행 역할에 적용할 수 있는 예시이며 자동 적용하지 않는다.
기본 SSE-S3 기준이며 SSE-KMS/별도 역할 위임 등 운영 구성이 다르면 추가 권한 검토가 필요하다.
실제 다운로드는 서명자의 GetObject 권한을 사용한다. 서명 URL을 가진 사람은 만료 전 접근할 수 있다.

## 검증과 DB

새 테이블/컬럼/수동 SQL 변경은 없다. 기존 배포 DB에는 선행 PR들의 마이그레이션이 필요하다.
실제 SDK로 요청/서명을 만들되 고정된 가짜 자격 증명과 모의 HTTP 전송을 사용한다.
SDK 체크섬 청크 전송을 모의 저장소에서 해석해 최종 이미지 바이트를 확인한다. 실제 S3 체크섬 검증을 실행한 것은 아니다.
로컬 HTTP 서버에서 응답 지연/시간 제한을 검증한다. H2에서는 이전 성공 보존·삭제 요청·참조 보호·늦은 업로드 보상 실패·워커 복구를 확인한다.
운영 AWS 호출·버킷/IAM 생성·실제 파일 삭제·운영 SQL 적용·실제 MySQL 검증은 수행하지 않는다.

## 공식 참고

- [AWS 기본 자격 증명 공급 체인](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html)
- [SDK 시간 제한](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html)
- [SDK 재시도](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html)
- [Apache 5 HTTP 클라이언트 설정](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/http/apache5/Apache5HttpClient.Builder.html)
- [S3 GET 서명](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html)
- [DeleteObject와 버전 관리](https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html)
