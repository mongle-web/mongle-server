package com.mongle.backend.domain.payment;

import static org.assertj.core.api.Assertions.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.credit.CreditService;
import com.mongle.backend.global.error.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:payment-orders;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "mongle.credit.enabled=true", "mongle.payment.enabled=true",
    "mongle.payment.products[0].id=pack", "mongle.payment.products[0].name=테스트충전",
    "mongle.payment.products[0].price=1000", "mongle.payment.products[0].credits=7", "mongle.payment.products[0].enabled=true"})
@ActiveProfiles("test")
class PaymentOrderIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false) static class Config {
        @Bean @Primary Stub gatewayStub() { return new Stub(); }
    }
    static class Stub implements PaymentGateway {
        final AtomicInteger count=new AtomicInteger();
        final AtomicInteger numbers=new AtomicInteger(20000);
        final Map<String,Command> commands=new ConcurrentHashMap<>();
        volatile CompletableFuture<Result> next;
        public boolean available() { return true; }
        public CompletableFuture<Result> request(Command command) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            count.incrementAndGet(); commands.put(command.orderId(),command);
            var future=next;
            return future==null?CompletableFuture.completedFuture(new Result(Outcome.READY,
                    Integer.toString(numbers.incrementAndGet()),"https://payapp.kr/test")):future;
        }
    }
    @Autowired PaymentService payments;
    @Autowired PaymentQueryService queries;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired PaymentTransactions transactions;
    @Autowired PaymentOrderRepository orders;
    @Autowired UserRepository users;
    @Autowired TokenService tokens;
    @Autowired CreditService credits;
    @Autowired Stub gateway;
    @Autowired TransactionTemplate transaction;
    @Value("${local.server.port}") int port;
    final JsonMapper json=JsonMapper.builder().build();
    Long user;
    @BeforeEach void setup() { user=users.saveAndFlush(User.create(UUID.randomUUID()+"@payment.test","결제")).getId(); gateway.next=null; }
    PaymentOrderRequest request() { return new PaymentOrderRequest(UUID.randomUUID(),"pack","01012345678"); }
    PaymentOrderResponse create(PaymentOrderRequest r) throws Exception { return payments.create(user,r).get(10,TimeUnit.SECONDS); }
    @Test void orderStatusIsOwnedNoStoreAndDoesNotExecuteProvider() throws Exception {
        var created=create(request()); int calls=gateway.count.get();
        assertThat(queries.get(user,created.orderId())).isEqualTo(created);
        Long other=users.saveAndFlush(User.create(UUID.randomUUID()+"@payment.test","다른계정")).getId();
        assertThatThrownBy(() -> queries.get(other,created.orderId())).isInstanceOf(BusinessException.class);
        try(var client=HttpClient.newHttpClient()) {
            var uri=URI.create("http://localhost:"+port+"/api/v1/payments/orders/"+created.orderId());
            var response=client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+tokens.login(user).response().accessToken()).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+tokens.login(other).response().accessToken()).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        }
        assertThat(gateway.count.get()).isEqualTo(calls);
    }
    @Test void abandonedRequestIsReportedAsUnknownWithoutResending() throws Exception {
        var created=create(request()); int calls=gateway.count.get();
        jdbc.update("update payment_orders set state='REQUESTING',request_deadline=? where public_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(60)),created.orderId());
        var response=queries.get(user,created.orderId());
        assertThat(response.state()).isEqualTo(PaymentOrder.State.UNKNOWN); assertThat(response.paymentUrl()).isNull();
        assertThat(gateway.count.get()).isEqualTo(calls); assertThat(credits.balance(user).total()).isZero();
    }
    @Test void orderHistoryUsesOwnedKeysetPaginationAndValidatesBounds() throws Exception {
        var first=create(request()); var second=create(request()); var third=create(request());
        Long other=users.saveAndFlush(User.create(UUID.randomUUID()+"@payment.test","다른계정")).getId();
        payments.create(other,request()).get(5,TimeUnit.SECONDS);
        var page=queries.page(user,null,2);
        assertThat(page.items()).extracting(PaymentOrderResponse::orderId).containsExactly(third.orderId(),second.orderId());
        assertThat(page.hasNext()).isTrue();
        var rest=queries.page(user,page.nextBeforeId(),2);
        assertThat(rest.items()).extracting(PaymentOrderResponse::orderId).containsExactly(first.orderId());
        assertThat(rest.hasNext()).isFalse(); assertThat(rest.nextBeforeId()).isNull();
        try(var client=HttpClient.newHttpClient()) {
            String token=tokens.login(user).response().accessToken();
            for(String query:List.of("size=0","size=51","beforeId=0")) {
                var uri=URI.create("http://localhost:"+port+"/api/v1/payments/orders?"+query);
                assertThat(client.send(HttpRequest.newBuilder(uri).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            }
            var spec=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(json.readTree(spec.body()).get("paths").get("/api/v1/payments/orders").get("get").get("responses").get("200").toString()).contains("PaymentOrderPage");
        }
    }
    @Test void persistsServerPriceAndDoesNotGrantOnPaymentUrl() throws Exception {
        var result=create(request());
        assertThat(result.price()).isEqualTo(1000); assertThat(result.credits()).isEqualTo(7);
        assertThat(result.state()).isEqualTo(PaymentOrder.State.READY);
        assertThat(result.creditGranted()).isFalse(); assertThat(credits.balance(user).total()).isZero();
        assertThat(orders.findByPublicId(result.orderId()).orElseThrow().getInputHash()).doesNotContain("01012345678");
    }
    @Test void sameRequestNeverCallsProviderTwiceAndChangedInputConflicts() throws Exception {
        var r=request(); int before=gateway.count.get();
        var first=create(r); assertThat(create(r)).isEqualTo(first);
        assertThat(gateway.count.get()).isEqualTo(before+1);
        assertThatThrownBy(() -> payments.create(user,new PaymentOrderRequest(r.requestId(),"pack","01099998888")))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo(PaymentErrorCode.REQUEST_CONFLICT));
    }
    @Test void concurrentRequestsCommitOneOrderAndMakeOneProviderCall() throws Exception {
        var r=request(); int before=gateway.count.get(); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var fs=new ArrayList<Future<PaymentOrderResponse>>();
            for(int i=0;i<4;i++) fs.add(pool.submit(() -> { start.await(); return create(r); }));
            start.countDown(); var ids=new HashSet<String>();
            for(var f:fs) ids.add(f.get(15,TimeUnit.SECONDS).orderId());
            assertThat(ids).hasSize(1); assertThat(gateway.count.get()).isEqualTo(before+1);
        }
    }
    @Test void inFlightDuplicateReturnsRequestingAndClientCancellationDoesNotCancelPersistence() throws Exception {
        gateway.next=new CompletableFuture<>(); var r=request(); var pending=payments.create(user,r);
        var duplicate=create(r); assertThat(duplicate.state()).isEqualTo(PaymentOrder.State.REQUESTING);
        pending.cancel(true);
        gateway.next.complete(new PaymentGateway.Result(PaymentGateway.Outcome.READY,"100001","https://payapp.kr/ready"));
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(orders.findByPublicId(duplicate.orderId()).orElseThrow().getState()==PaymentOrder.State.REQUESTING && System.nanoTime()<end) Thread.onSpinWait();
        assertThat(orders.findByPublicId(duplicate.orderId()).orElseThrow().getState()).isEqualTo(PaymentOrder.State.READY);
    }
    @Test void uncertainAndRejectedResultsArePersistedAndNeverAutomaticallyRetried() throws Exception {
        for(var outcome:List.of(PaymentGateway.Result.unknown(),PaymentGateway.Result.rejected())) {
            gateway.next=CompletableFuture.completedFuture(outcome); var r=request(); int before=gateway.count.get();
            var result=create(r); assertThat(result.paymentUrl()).isNull();
            assertThat(result.state()).isEqualTo(outcome.outcome()==PaymentGateway.Outcome.UNKNOWN?PaymentOrder.State.UNKNOWN:PaymentOrder.State.REQUEST_REJECTED);
            assertThat(create(r)).isEqualTo(result); assertThat(gateway.count.get()).isEqualTo(before+1);
        }
    }
    @Test void providerTransactionCannotBelongToTwoOrders() throws Exception {
        gateway.next=CompletableFuture.completedFuture(new PaymentGateway.Result(PaymentGateway.Outcome.READY,"100002","https://payapp.kr/ready"));
        create(request());
        assertThatThrownBy(() -> create(request())).hasCauseInstanceOf(BusinessException.class);
    }
    @Test void configuredInactiveProductAndInvalidPhoneDoNotCallProvider() {
        int before=gateway.count.get();
        assertThatThrownBy(() -> payments.create(user,new PaymentOrderRequest(UUID.randomUUID(),"missing","01012345678"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> payments.create(user,new PaymentOrderRequest(UUID.randomUUID(),"pack","phone"))).isInstanceOf(BusinessException.class);
        assertThat(gateway.count.get()).isEqualTo(before);
    }
    @Test void externalRequestCannotJoinAnOuterDatabaseTransaction() {
        assertThatThrownBy(() -> transaction.execute(s -> payments.create(user,request())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void orderHttpUsesServerPriceHidesSecretsAndValidatesPhone() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var uri=URI.create("http://localhost:"+port+"/api/v1/payments/orders");
            String token=tokens.login(user).response().accessToken();
            var b=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+token).header("Content-Type","application/json");
            var response=client.send(b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request()))).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).at("/data/price").asLong()).isEqualTo(1000);
            assertThat(response.body()).doesNotContain("01012345678","notificationToken","inputHash","providerTransaction");
            var invalid=client.send(b.POST(HttpRequest.BodyPublishers.ofString("{\"requestId\":\""+UUID.randomUUID()+"\",\"productId\":\"pack\",\"buyerPhone\":\"invalid\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(invalid.statusCode()).isEqualTo(400);
        }
    }
}
