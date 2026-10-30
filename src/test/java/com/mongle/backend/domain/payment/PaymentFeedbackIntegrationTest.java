package com.mongle.backend.domain.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:payment-feedback;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.credit.enabled=true", "mongle.credit.dream-image-generation=2", "mongle.payment.enabled=true",
    "mongle.payment.products[0].id=pack", "mongle.payment.products[0].name=테스트충전",
    "mongle.payment.products[0].price=1000", "mongle.payment.products[0].credits=7", "mongle.payment.products[0].enabled=true",
    "mongle.payment.payapp.enabled=true", "mongle.payment.payapp.seller-id=seller", "mongle.payment.payapp.link-key=test-link",
    "mongle.payment.payapp.link-value=test-value", "mongle.payment.payapp.request-key=test-secret",
    "mongle.payment.payapp.feedback-url=https://api.mongle.test/api/v1/payments/payapp/feedback",
    "mongle.payment.payapp.return-url=https://mongle.test/payment"})
@Import(PaymentOrderIntegrationTest.Config.class)
@ActiveProfiles("test")
class PaymentFeedbackIntegrationTest {
    @Autowired PaymentService payments;
    @Autowired PaymentOrderRepository orders;
    @Autowired PaymentFeedbackService feedback;
    @Autowired PayAppFeedbackVerifier verifier;
    @Autowired UserRepository users;
    @Autowired CreditService credits;
    @Autowired CreditReservationService reservations;
    @Autowired PaymentOrderIntegrationTest.Stub gateway;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PaymentNotificationRepository notifications;
    @Value("${local.server.port}") int port;
    Long user; PaymentOrder order;
    @BeforeEach void setup() throws Exception {
        user=users.saveAndFlush(User.create(UUID.randomUUID()+"@feedback.test","결제")).getId(); gateway.next=null;
        var response=payments.create(user,new PaymentOrderRequest(UUID.randomUUID(),"pack","01012345678")).get(5,TimeUnit.SECONDS);
        order=orders.findByPublicId(response.orderId()).orElseThrow();
    }
    Map<String,String> event(int state) { return event(state,Instant.now()); }
    Map<String,String> event(int state,Instant respondedAt) {
        var m=new LinkedHashMap<String,String>();
        m.put("userid","seller"); m.put("linkkey","test-link"); m.put("linkval","test-value");
        m.put("responsedate",DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.of("Asia/Seoul")).format(respondedAt));
        m.put("hash",PaymentHashes.sha256("seller"+m.get("responsedate")+"test-secret"));
        m.put("var1",order.getPublicId()); m.put("var2",order.getNotificationToken());
        m.put("mul_no",order.getProviderTransaction()); m.put("price","1000"); m.put("pay_state",Integer.toString(state)); return m;
    }
    void accept(Map<String,String> m) { feedback.accept(verifier.verify(m)); }
    PaymentOrder current() { return orders.findById(order.getId()).orElseThrow(); }
    long events() { return jdbc.queryForObject("select count(*) from payment_notifications where order_id=?",Long.class,order.getId()); }
    HttpResponse<String> post(String body) throws Exception {
        try(var c=HttpClient.newHttpClient()) {
            return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/payments/payapp/feedback"))
                    .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void verifiedCompletionGrantsOnceAndReturnsPlainSuccessWithoutJwt() throws Exception {
        var m=event(4); m.put("unknown_future_field","accepted");
        var response=post(PayAppForm.encode(m));
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).isEqualTo("SUCCESS");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/plain");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(post(PayAppForm.encode(m)).body()).isEqualTo("SUCCESS");
        assertThat(credits.balance(user).available()).isEqualTo(7); assertThat(current().getState()).isEqualTo(PaymentOrder.State.PAID);
        assertThat(events()).isEqualTo(1);
    }
    @Test void authenticationHashTimeAmountAndCorrelationAreAllRequired() throws Exception {
        for(String key:List.of("userid","linkkey","linkval","hash","var1","var2","price","mul_no","responsedate","pay_state")) {
            var m=event(4); m.put(key,key.equals("price")?"999":key.equals("mul_no")?"0002":"invalid");
            var response=post(PayAppForm.encode(m));
            assertThat(response.statusCode()).isBetween(400,499); assertThat(response.body()).isEqualTo("FAIL");
            assertThat(credits.balance(user).total()).isZero(); assertThat(events()).isZero();
        }
    }
    @Test void staleNewFeedbackIsRejectedButStoredExactDuplicateIsAcknowledged() {
        var m=event(4); String old=DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.of("Asia/Seoul")).format(Instant.now().minusSeconds(3600));
        m.put("responsedate",old); m.put("hash",PaymentHashes.sha256("seller"+old+"test-secret"));
        assertThatThrownBy(() -> accept(m)).isInstanceOf(BusinessException.class);
        assertThat(credits.balance(user).total()).isZero(); assertThat(events()).isZero();
        var verified=verifier.verify(m);
        // 과거에 커밋된 동일 통보의 재전송을 재현한다. 재전송 자체가 지급을 수행하지 않아야 한다.
        notifications.saveAndFlush(PaymentNotification.create(verified,order.getId()));
        accept(m); assertThat(credits.balance(user).total()).isZero(); assertThat(events()).isEqualTo(1);
    }
    @Test void concurrentCompletionsGrantOneLedgerEntry() throws Exception {
        var m=event(4); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var fs=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++) fs.add(pool.submit(() -> { start.await(); accept(m); return null; }));
            start.countDown(); for(var f:fs) f.get(10,TimeUnit.SECONDS);
        }
        assertThat(credits.balance(user).available()).isEqualTo(7); assertThat(events()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id=? and kind='GRANT'",Long.class,user)).isEqualTo(1);
    }
    @Test void lateRequestAndPendingEventsCannotUndoPaidOrder() {
        accept(event(4)); accept(event(10)); accept(event(1));
        assertThat(current().getState()).isEqualTo(PaymentOrder.State.PAID); assertThat(credits.balance(user).available()).isEqualTo(7);
    }
    @Test void callbackCanPrecedeRequestResponseWithoutLosingPayment() throws Exception {
        gateway.next=new CompletableFuture<>(); var request=new PaymentOrderRequest(UUID.randomUUID(),"pack","01012345678");
        var pending=payments.create(user,request); order=orders.findByUserIdAndRequestId(user,request.requestId().toString()).orElseThrow();
        var m=event(4); m.put("mul_no","9000001"); accept(m);
        gateway.next.complete(new PaymentGateway.Result(PaymentGateway.Outcome.READY,"9000001","https://payapp.kr/ready"));
        assertThat(pending.get(5,TimeUnit.SECONDS).state()).isEqualTo(PaymentOrder.State.PAID);
        assertThat(current().getPaymentUrl()).isNull(); assertThat(credits.balance(user).available()).isEqualTo(7);
    }
    @Test void transactionCannotBeMovedToAnotherOrderOrUsedAsPartialCancellationId() throws Exception {
        String owned=order.getProviderTransaction();
        var response=payments.create(user,new PaymentOrderRequest(UUID.randomUUID(),"pack","01012345678")).get(5,TimeUnit.SECONDS);
        order=orders.findByPublicId(response.orderId()).orElseThrow();
        var m=event(4); m.put("mul_no",owned);
        assertThatThrownBy(() -> accept(m)).isInstanceOf(BusinessException.class);
        var partial=event(70); partial.put("orig_mul_no",order.getProviderTransaction()); partial.put("orig_price","1000"); partial.put("price","500"); partial.put("mul_no",owned);
        assertThatThrownBy(() -> accept(partial)).isInstanceOf(BusinessException.class);
        assertThat(credits.balance(user).available()).isZero(); assertThat(events()).isZero();
    }
    @Test void creditGrantAndNotificationRollbackWithOrderIfFinalStorageFails() throws Exception {
        doThrow(new IllegalStateException("테스트 저장 실패")).when(notifications).saveAndFlush(any(PaymentNotification.class));
        var response=post(PayAppForm.encode(event(4)));
        assertThat(response.statusCode()).isEqualTo(503); assertThat(response.body()).isEqualTo("FAIL");
        assertThat(credits.balance(user).total()).isZero(); assertThat(current().getState()).isEqualTo(PaymentOrder.State.READY);
        assertThat(current().getCreditGrantId()).isNull(); assertThat(events()).isZero();
    }
    @Test void cancelledUnpaidOrderCannotBeCreditedByLateCompletion() {
        accept(event(8)); assertThat(current().getState()).isEqualTo(PaymentOrder.State.CANCELLED);
        accept(event(4)); assertThat(current().getState()).isEqualTo(PaymentOrder.State.PAYMENT_REVIEW);
        assertThat(current().getCreditGrantId()).isNull(); assertThat(credits.balance(user).total()).isZero();
    }
    @Test void approvedCancellationIsIdempotentAndBlocksFurtherSpendingWithoutInventingRefundAmounts() {
        accept(event(4)); var reservation=reservations.reserve(user,"attempt-1",CreditUsage.DREAM_IMAGE_GENERATION,"image-1",Instant.now().plusSeconds(120));
        var cancel=event(9); accept(cancel); accept(cancel);
        assertThat(current().getState()).isEqualTo(PaymentOrder.State.PAYMENT_REVIEW);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(5,2,7,true));
        assertThatThrownBy(() -> reservations.reserve(user,"attempt-2",CreditUsage.DREAM_IMAGE_GENERATION,"image-2",Instant.now().plusSeconds(120)))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(CreditErrorCode.PAYMENT_REVIEW));
        assertThatThrownBy(() -> reservations.settle(user,reservation.reservationId(),"result-1")).isInstanceOf(BusinessException.class);
        reservations.release(user,reservation.reservationId(),CreditReservation.ReleaseReason.FAILURE);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(7,0,7,true));
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id=? and kind='PAYMENT_HOLD'",Long.class,user)).isEqualTo(1);
        assertThatThrownBy(() -> payments.create(user,new PaymentOrderRequest(UUID.randomUUID(),"pack","01012345678"))).isInstanceOf(BusinessException.class);
    }
    @Test void cancellationRollsBackHoldAndOrderTogetherIfNotificationStorageFails() {
        accept(event(4));
        doThrow(new IllegalStateException("통보 저장 실패")).when(notifications).saveAndFlush(any(PaymentNotification.class));
        assertThatThrownBy(() -> accept(event(9))).isInstanceOf(RuntimeException.class);
        assertThat(current().getState()).isEqualTo(PaymentOrder.State.PAID);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(7,0,7));
        assertThat(jdbc.queryForObject("select count(*) from credit_payment_holds where user_id=?",Long.class,user)).isZero();
    }
    @Test void cancellationAfterConsumptionPreservesLedgerAndBlocksRemainingCredits() {
        accept(event(4));
        var reservation=reservations.reserve(user,"consumed",CreditUsage.DREAM_IMAGE_GENERATION,"consumed-image",Instant.now().plusSeconds(120));
        reservations.settle(user,reservation.reservationId(),"saved-image"); accept(event(64));
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(5,0,5,true));
        assertThat(current().getState()).isEqualTo(PaymentOrder.State.PAYMENT_REVIEW);
    }
    @Test void partialCancellationChecksOriginalAmountAndTracksSeparateTransaction() {
        var paid=event(4); accept(paid); var m=event(70); m.put("orig_mul_no",order.getProviderTransaction()); m.put("orig_price","999"); m.put("price","500"); m.put("mul_no","9000002");
        assertThatThrownBy(() -> accept(m)).isInstanceOf(BusinessException.class);
        assertThat(credits.balance(user).spendingBlocked()).isFalse();
        m.put("orig_price","1000"); accept(m); accept(m); accept(paid);
        assertThat(current().getState()).isEqualTo(PaymentOrder.State.PAYMENT_REVIEW);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(7,0,7,true));
        assertThat(events()).isEqualTo(2);
    }
    @Test void completionWithNewResponseTimeAfterPartialCancellationDoesNotGrantAgain() {
        Instant respondedAt=Instant.now().minusSeconds(1);
        var paid=event(4,respondedAt); accept(paid);
        var cancel=event(70); cancel.put("orig_mul_no",order.getProviderTransaction());
        cancel.put("orig_price","1000"); cancel.put("price","500"); cancel.put("mul_no","8"+order.getProviderTransaction());
        accept(cancel);
        var laterPaid=event(4,respondedAt.plusSeconds(1));
        assertThat(verifier.verify(laterPaid).eventHash()).isNotEqualTo(verifier.verify(paid).eventHash());
        accept(laterPaid); accept(laterPaid);
        assertThat(current().getState()).isEqualTo(PaymentOrder.State.PAYMENT_REVIEW);
        assertThat(credits.balance(user)).isEqualTo(new CreditBalance(7,0,7,true));
        assertThat(events()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from credit_ledger where user_id=? and kind='GRANT'",Long.class,user)).isEqualTo(1);
    }
    @Test void paidAndCancelledRaceNeverLeavesSpendableCancelledCredits() throws Exception {
        var paid=event(4); var cancelled=event(9); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { start.await(); accept(paid); return null; });
            var b=pool.submit(() -> { start.await(); accept(cancelled); return null; });
            start.countDown(); a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
        }
        var balance=credits.balance(user);
        assertThat(balance.total()==0 || balance.spendingBlocked()).isTrue();
        assertThat(current().getState()).isIn(PaymentOrder.State.CANCELLED,PaymentOrder.State.PAYMENT_REVIEW);
    }
    @Test void oversizedAndDuplicateFormKeysAreRejectedWithoutEchoingPayload() throws Exception {
        var m=event(4);
        assertThat(post(PayAppForm.encode(m)+"&price=1000").statusCode()).isEqualTo(400);
        var response=post("payload="+"x".repeat(33000));
        assertThat(response.statusCode()).isEqualTo(413); assertThat(response.body()).isEqualTo("FAIL");
        assertThat(events()).isZero(); assertThat(credits.balance(user).total()).isZero();
    }
    @Test void onlyPostFeedbackIsPublicAndSwaggerDoesNotRequireBearerForIt() throws Exception {
        try(var c=HttpClient.newHttpClient()) {
            for(String path:List.of("/api/v1/payments/payapp/feedback","/api/v1/payments/orders","/api/v1/payments/products"))
                assertThat(c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            var spec=c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var node=tools.jackson.databind.json.JsonMapper.builder().build().readTree(spec.body()).get("paths").get("/api/v1/payments/payapp/feedback").get("post");
            assertThat(node.get("security")==null || node.get("security").isEmpty()).isTrue();
            assertThat(node.get("requestBody").toString()).contains("application/x-www-form-urlencoded");
        }
    }
}
