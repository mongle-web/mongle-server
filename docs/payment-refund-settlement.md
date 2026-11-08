# 페이앱 환불 결과 대조와 정산 (#134)

판매자 계정에서 처리한 환불 결과를 몽글 내부 주문과 대조한 뒤 구매분을 회수한다.
명령은 애플리케이션 서버나 워커를 띄우지 않고, 지정한 MySQL에 검증 TLS로 접속한다.
페이앱 판매자 화면에서 전액·부분취소와 정산 후 반환 절차를 확인하는 단계가 선행된다.
[페이앱 결제 취소와 통보 명세](https://docs.payapp.kr/dev_center01.html)를 참고한다.

## 권한과 설정

운영 DB에 접근할 수 있는 지정 운영자만 새 JAR의 명령을 실행한다. 아래 변수를 해당 명령의 환경에 주입한다.

| 변수 | 값 |
| --- | --- |
| `MONGLE_PAYMENT_REFUND_OPS_ENABLED` | 명령 실행 시 true |
| `MONGLE_PAYMENT_REFUND_OPS_EXPECTED_HOST` | 지정 MySQL 호스트 |
| `MONGLE_PAYMENT_REFUND_OPS_EXPECTED_DATABASE` | 지정 DB 이름 |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 기존 검증 대상 URL과 `mongle_app` 계정 |
| `DB_TRUSTSTORE_URL`, `DB_TRUSTSTORE_PASSWORD` | 기존 PKCS12 신뢰 저장소 |

`inspect`와 `preview`는 읽기 전용이다. `apply`는 해당 사용자 행 잠금을 잡고 최신 상태를 검증한 뒤 한 번의 트랜잭션으로 처리한다.
운영 근거에는 승인자, 판매자 화면 확인 결과와 고객센터 케이스를 기록한다. 명령에는 해당 기록의 식별자만 넘긴다.

## 전액·부분취소 정산

1. 접수번호로 원주문, 구매액, 사용·예약·미사용 수량과 진행 상태를 확인한다.

   ```bash
   java -jar "$MONGLE_JAR" --payment-refund=inspect --receipt-id="$RECEIPT_ID"
   ```

2. 페이앱 판매자 화면에서 주문 귀속과 취소 가능한 금액을 확인한다. 일반 환불은 `standardEstimatedAmount`를 사용한다. 법정 청약철회·서비스 하자·승인일 불명처럼 별도 판단이 필요하면 최종 승인 금액과 근거를 운영 기록에 남기고 `REVIEW`로 처리한다. 정산 후 반환 대기 또는 기존 취소 결과가 불명확하면 확인이 끝날 때까지 접수 상태를 유지한다.
3. 실제 취소를 처리한 뒤 금액과 회수할 크레딧을 각각 입력한다. 예시는 1,100원에 10개를 구매해 3개를 사용했고 일반 환불의 공제가 없는 경우다. 이미 예약한 수량은 이미지 처리 또는 탈퇴 해제로 확정한 뒤 진행한다.

   ```bash
   java -jar "$MONGLE_JAR" --payment-refund=preview --receipt-id="$RECEIPT_ID" \
     --action=settle --amount=770 --credits=7 --basis=STANDARD --operator-reference=case-134-001
   ```

4. 판매자 화면의 최종 취소와 `decision=READY`를 모두 확인하고, 같은 입력에 미리보기의 `snapshot`을 더해 실행한다.

   ```bash
   java -jar "$MONGLE_JAR" --payment-refund=apply --receipt-id="$RECEIPT_ID" \
     --action=settle --amount=770 --credits=7 --basis=STANDARD --operator-reference=case-134-001 \
     --expected-snapshot="$REFUND_SNAPSHOT"
   ```

전액 취소(9/64)는 원거래 식별자·원결제액을 대조한다. 부분취소(70/71)는 콜백에서 검증한 원주문 귀속과 별도 취소 거래 식별자를 대조한다.
동일 취소 거래의 반복 통보는 한 번만 합산하고, 기존 취소를 포함한 합계는 승인 금액과 정확히 일치하며 원결제액 이하여야 한다.
원주문 결제완료 통보보다 앞선 취소, 취소 금액 충돌, 원거래와 같은 식별자의 부분취소 등은 확인 대상으로 남긴다.
여러 부분취소 뒤 전액취소 통보가 함께 들어와 합계가 맞지 않으면 판매자 화면에서 대조하고 보류를 유지한다.
정산 후 돌아온 동일 통보는 정산을 반복하지 않는다. 새 취소 증빙이 오면 해당 주문을 다시 검토 상태로 보내고 구매분 보류를 건다.

정산할 때 남은 해당 구매분 전량을 회수한다. 환불액이 공제 등으로 줄어도 수량은 원화에서 역산하지 않는다.
이미 전량 사용한 별도 검토 건은 회수 수량 0으로 실제 확인된 환불액만 기록할 수 있다.
완료 시 회수·원장·접수 종료·해당 결제 보류 해제를 함께 커밋한다. 다른 주문의 보류는 따로 유지한다.
같은 입력·스냅샷의 재실행은 기존 결과를 반환한다. `SNAPSHOT_CHANGED`이면 최신 결과를 확인한 뒤 미리보기부터 다시 진행한다.

## 반려와 철회

페이앱에서 취소 실행·취소 요청·정산 후 반환 대기가 없음을 확인한 뒤 `--action=reject` 또는 `--action=withdraw`를 사용한다.
`--amount`, `--credits`, `--basis` 없이 먼저 `preview`, 같은 인자와 스냅샷으로 `apply`를 실행한다.
취소 통보·외부 보류가 있거나 원주문이 결제 완료 상태가 아니면 접수 상태를 유지한다.
반려·철회 근거는 운영 참조 번호로 남긴다. 종료된 반려·철회는 보존하며, 다시 신청할 때 새 요청 UUID를 사용한다.

## 프론트와 운영 인계

- 환불 상세·목록의 `processingStatus`는 `RECEIVED`, `COMPLETED`, `REJECTED`, `WITHDRAWN`이다. 접수 중에는 `currentEstimate`, 종료 후에는 `resolution`의 환불 금액·회수량·완료 시각을 표시한다.
- 주문 상태 `REFUND_SETTLED`는 충전 확인 폴링을 끝내고 환불 완료를 표시한다. `PAYMENT_REVIEW`는 별도 확인이 필요한 상태다. 접수번호로 문의할 때 운영자는 신청자와 원주문을 대조한다.
- V10을 CD로 적용한다. V9 접수와 원장은 보존된다. 정산이 열린 동안은 탈퇴 기록을 파기하지 않으며, 완료된 환불의 보관 기한에는 최종 처리 시각을 반영한다.
- 환불 접수 공개 전 대상 판매자 계정의 전액·부분취소, 정산 후 반환, 실제 취소 통보, 탈퇴 후 문의를 운영자와 검증한다. 현재 공개 플래그 기본값은 false다.
- 운영 정산이 시작되면 `REFUND_SETTLED`와 정산 기록을 읽는 버전으로 운영한다. 일시 중지는 신규 접수 플래그를 내려 처리한다.
