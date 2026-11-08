package com.mongle.backend.domain.payment;

import com.mongle.backend.domain.auth.service.TokenService;
import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.withdrawal.WithdrawalRetention;
import com.mongle.backend.domain.user.withdrawal.WithdrawalService;
import com.mongle.backend.global.error.BusinessException;
import com.mongle.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static com.mongle.backend.domain.payment.PaymentRefundRequest.Reason.*;
import static com.mongle.backend.domain.payment.RefundEstimate.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-intake;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "mongle.credit.enabled=true", "mongle.credit.dream-image-generation=3",
        "mongle.payment.refund.enabled=true", "mongle.payment.refund.support-email=help@example.invalid",
        "mongle.legal.published=true", "mongle.legal.enforce=true",
        "mongle.legal.terms-digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "mongle.legal.privacy-digest=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "mongle.withdrawal.enabled=true", "mongle.withdrawal.worker-enabled=false",
        "mongle.withdrawal.identity-key=withdrawal-test-identity-key-at-least-32-characters",
        "mongle.withdrawal.encryption-key=withdrawal-test-encryption-key-at-least-32-characters",
        "mongle.payment.payapp.enabled=true", "mongle.payment.payapp.seller-id=seller",
        "mongle.payment.payapp.link-key=test-link", "mongle.payment.payapp.link-value=test-value",
        "mongle.payment.payapp.request-key=test-secret",
        "mongle.payment.payapp.feedback-url=https://api.mongle.test/api/v1/payments/payapp/feedback",
        "mongle.payment.payapp.return-url=https://mongle.test/payment"
})
@ActiveProfiles("test")
class RefundIntakeIntegrationTest {
    @Autowired UserRepository users;
    @Autowired PaymentOrderRepository orders;
    @Autowired RefundService refunds;
    @Autowired PaymentFeedbackService feedback;
    @Autowired PayAppFeedbackVerifier verifier;
    @Autowired CreditService credits;
    @Autowired CreditReservationService reservations;
    @Autowired TokenService tokens;
    @Autowired WithdrawalService withdrawals;
    @Autowired WithdrawalRetention retention;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean(name = "authClock") Clock clock;
    @Value("${local.server.port}") int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private Instant now;
    private long user;

    @BeforeAll
    static void initAccountingFixture(@Autowired JdbcTemplate jdbc) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null,
                    "payment_credit_reclamations", null)) {
                if (tables.next()) return null;
            }
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__payment_credit_reclamations.sql"))
                    .populate(connection);
            return null;
        });
    }

    @BeforeEach
    void setup() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        when(clock.instant()).thenAnswer(ignored -> now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        user = member();
    }

    @Test
    void calculatesPartialAmountWithSingleRoundingAndExactSevenDayBoundary() {
        var order = purchase(7, now.minus(Duration.ofDays(7)));
        assertThat(refunds.estimate(user, order, STANDARD).estimatedAmount()).isEqualTo(1100);
        reservations.settle(user, reserve("used").reservationId(), "saved-image");
        credits.grant(user, CreditSource.PROMOTION, "free-after:" + user, 10);

        var atBoundary = refunds.estimate(user, order, STANDARD);
        assertThat(atBoundary).satisfies(e -> {
            assertThat(e.usedCredits()).isEqualTo(3);
            assertThat(e.unusedCredits()).isEqualTo(4);
            assertThat(e.feePercent()).isZero();
            assertThat(e.estimatedAmount()).isEqualTo(629);
        });

        now = now.plus(1, ChronoUnit.MICROS);
        var receipt = refunds.receive(user, request(order, STANDARD));
        assertThat(receipt.feePercentAtRequest()).isEqualTo(10);
        assertThat(receipt.estimatedAmountAtRequest()).isEqualTo(566);
        assertThat(receipt.currentEstimate().status()).isEqualTo(ESTIMATED);
    }

    @Test
    void missingApprovalAndRightsClaimsRemainReviewable() {
        var historical = purchase(10, null);
        var old = refunds.receive(user, request(historical, STANDARD));
        assertThat(old.initialStatus()).isEqualTo(REVIEW_REQUIRED);
        assertThat(old.estimatedAmountAtRequest()).isNull();
        assertThat(old.feePercentAtRequest()).isNull();

        var consumed = purchase(3, now.minus(Duration.ofDays(14)));
        reservations.settle(user, reserve("fully-used").reservationId(), "saved-image");
        assertThat(refunds.estimate(user, consumed, STANDARD).status()).isEqualTo(NO_REFUNDABLE_BALANCE);
        assertError(() -> refunds.receive(user, request(consumed, STANDARD)), RefundError.INELIGIBLE);
        var rights = refunds.receive(user, request(consumed, RIGHTS_REVIEW));
        assertThat(rights.currentEstimate().status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(rights.currentEstimate().estimatedAmount()).isNull();
    }

    @Test
    void holdBlocksOnlyTheRequestedPurchaseAndExistingReleaseRestoresItForRecalculation() {
        var heldOrder = purchase(10, now);
        var inFlight = reserve("in-flight");
        var otherOrder = purchase(3, now);
        var request = request(heldOrder, STANDARD);
        var initial = refunds.receive(user, request);
        assertThat(initial.initialStatus()).isEqualTo(WAITING_RESERVATION);
        assertThat(initial.unusedAtRequest()).isEqualTo(7);
        assertThat(initial.reservedAtRequest()).isEqualTo(3);
        assertThat(initial.estimatedAmountAtRequest()).isNull();
        assertError(() -> withdrawals.withdraw(user), UserErrorCode.REFUND_REQUIRED);

        reservations.settle(user, reserve("other-lot").reservationId(), "other-image");
        assertThat(refunds.estimate(user, otherOrder, STANDARD).usedCredits()).isEqualTo(3);
        assertError(() -> reserve("held-only"), CreditErrorCode.INSUFFICIENT);
        credits.grant(user, CreditSource.ATTENDANCE, "free-unheld:" + user, 3);
        reservations.settle(user, reserve("free-unheld").reservationId(), "free-image");

        now = now.plus(Duration.ofDays(8));
        reservations.release(user, inFlight.reservationId(), CreditReservation.ReleaseReason.FAILURE);
        var refreshed = refunds.receive(user, request);
        assertThat(refreshed.receiptId()).isEqualTo(initial.receiptId());
        assertThat(refreshed.reservedAtRequest()).isEqualTo(3);
        assertThat(refreshed.currentEstimate().unusedCredits()).isEqualTo(10);
        assertThat(refreshed.currentEstimate().estimatedAmount()).isEqualTo(1100);
        assertThat(refreshed.currentEstimate().feePercent()).isZero();
        assertError(() -> reserve("released-but-held"), CreditErrorCode.INSUFFICIENT);
    }

    @Test
    void existingReservationCanSettleAndVerifiedCancellationMovesReceiptToReview() {
        var paid = purchase(10, now);
        var reservation = reserve("before-intake");
        var receipt = refunds.receive(user, request(paid, STANDARD));
        reservations.settle(user, reservation.reservationId(), "completed-image");
        var id = UUID.fromString(receipt.receiptId());
        assertThat(refunds.get(user, id).currentEstimate().estimatedAmount()).isEqualTo(770);

        var order = orders.findByPublicId(paid.toString()).orElseThrow();
        feedback.accept(verifier.verify(notification(order, 9, now)));
        var reviewed = refunds.get(user, id);
        assertThat(reviewed.currentEstimate().status()).isEqualTo(REVIEW_REQUIRED);
        assertThat(reviewed.currentEstimate().estimatedAmount()).isNull();
        assertThat(reviewed.currentEstimate().unusedCredits()).isEqualTo(7);
        assertThat(credits.balance(user).spendingBlocked()).isTrue();
        assertThat(refunds.page(user, null, 20).items()).hasSize(1);
    }

    @Test
    void concurrentDuplicateRequestsCommitOnlyOneHoldAndRejectChangedInputs() throws Exception {
        var paid = purchase(10, now);
        var request = request(paid, STANDARD);
        var beforeVersion = accountVersion();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<RefundReceipt> task = () -> {
                if (!gate.await(10, TimeUnit.SECONDS)) throw new AssertionError("request start timeout");
                return refunds.receive(user, request);
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            gate.countDown();
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(first.get(15, TimeUnit.SECONDS));
        }
        assertThat(accountVersion()).isEqualTo(beforeVersion + 1);
        assertThat(refunds.page(user, null, 20).items()).hasSize(1);
        assertError(() -> refunds.receive(user, request(paid, STANDARD)), RefundError.CONFLICT);
        assertError(() -> refunds.receive(user, new RefundRequest(request.requestId(), paid, RIGHTS_REVIEW)),
                RefundError.CONFLICT);
    }

    @Test
    void staleOuterSnapshotCannotReserveAfterRefundIntake() {
        var paid = purchase(10, now);
        var outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> outer.execute(status -> {
            assertThat(credits.balance(user).available()).isEqualTo(10);
            try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(() -> refunds.receive(user, request(paid, STANDARD))).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError(e);
            }
            return reserve("stale-snapshot");
        })).satisfiesAnyOf(
                e -> assertThat(e).isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class),
                e -> assertThat(e).isInstanceOfSatisfying(BusinessException.class,
                        problem -> assertThat(problem.getErrorCode()).isEqualTo(CreditErrorCode.INSUFFICIENT)));
        assertThat(credits.balance(user).reserved()).isZero();
        assertThat(refunds.page(user, null, 20).items()).hasSize(1);
    }

    @Test
    void authenticatedIntakeAndWithdrawalPreserveOpenReceiptsAndIsolateOtherMembers() throws Exception {
        var paid = purchase(10, now);
        var second = purchase(10, now);
        var pending = reserve("cancel-on-withdrawal");
        var request = request(paid, STANDARD);
        String body = json.writeValueAsString(request);
        String bearer = tokens.login(user).response().accessToken();
        String another = tokens.login(member()).response().accessToken();
        String path = "/api/v1/payments/refunds";
        try (var client = HttpClient.newHttpClient()) {
            assertThat(http(client, null, "POST", path, body).statusCode()).isEqualTo(401);
            assertThat(http(client, another, "POST", path, body).statusCode()).isEqualTo(404);
            assertThat(http(client, bearer, "GET", path + "/estimate?orderId=" + paid, null).statusCode()).isEqualTo(200);
            var response = http(client, bearer, "POST", path, body);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            var data = json.readTree(response.body()).path("data");
            String id = data.path("receiptId").asString();
            assertThat(data.path("supportEmail").asString()).isEqualTo("help@example.invalid");
            assertThat(response.body()).doesNotContain("providerTransaction", "notificationToken", "grantId");
            assertThat(http(client, another, "GET", path + "/" + id, null).statusCode()).isEqualTo(404);
            assertThat(http(client, bearer, "DELETE", "/api/v1/users/me", null).body()).contains("USER_409_2");
            refunds.receive(user, request(second, STANDARD));
            assertThat(refunds.page(user, null, 1).hasNext()).isTrue();
            assertThat(http(client, bearer, "DELETE", "/api/v1/users/me", null).statusCode()).isEqualTo(200);
            assertThat(http(client, bearer, "GET", path + "/" + id, null).statusCode()).isEqualTo(401);
        }

        assertThat(jdbc.queryForObject("select release_reason from credit_reservations where id=?",
                String.class, pending.reservationId())).isEqualTo("CANCELLED");
        now = now.plus(Duration.ofDays(6 * 365));
        assertThat(retention.purge(user)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from payment_refund_requests where user_id=?",
                Long.class, user)).isEqualTo(2);
        assertThat(users.findById(user).orElseThrow().isWithdrawn()).isTrue();
    }

    @Test
    void storesOnlyVerifiedApprovalTimeAndNeverReplacesItOnReplay() {
        var order = readyOrder(10);
        var approved = order.getCreatedAt().toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        now = approved.plusSeconds(10);
        var form = notification(order, 4, now);
        form.put("pay_date", DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"))
                .format(approved));
        var verified = verifier.verify(form);
        assertThat(verified.approvedAt()).isEqualTo(approved);
        feedback.accept(verified);
        var current = orders.findById(order.getId()).orElseThrow();
        assertThat(current.getApprovedAt()).isEqualTo(approved);
        form.put("pay_date", "malformed");
        feedback.accept(verifier.verify(form));
        assertThat(orders.findById(order.getId()).orElseThrow().getApprovedAt()).isEqualTo(approved);
        assertThat(credits.balance(user).available()).isEqualTo(10);

        var other = readyOrder(10);
        var invalidDate = notification(other, 4, now);
        invalidDate.put("pay_date", "20991231235959");
        feedback.accept(verifier.verify(invalidDate));
        assertThat(orders.findById(other.getId()).orElseThrow().getState()).isEqualTo(PaymentOrder.State.PAID);
        assertThat(refunds.estimate(user, UUID.fromString(other.getPublicId()), STANDARD).status())
                .isEqualTo(REVIEW_REQUIRED);
    }

    private long member() {
        return users.saveAndFlush(User.create(UUID.randomUUID() + "@refund.test", "몽글")).getId();
    }

    private UUID purchase(long quantity, Instant approvedAt) {
        var order = readyOrder(quantity);
        var grant = credits.grant(user, CreditSource.PAYMENT, PaymentFeedbackService.sourceKey(order), quantity);
        jdbc.update("update payment_orders set state='PAID', credit_grant_id=?, approved_at=? where id=?",
                grant.grantId(), approvedAt == null ? null : LocalDateTime.ofInstant(approvedAt, ZoneOffset.UTC),
                order.getId());
        return UUID.fromString(order.getPublicId());
    }

    private PaymentOrder readyOrder(long quantity) {
        var order = orders.saveAndFlush(PaymentOrder.create(user,
                new PaymentOrderRequest(UUID.randomUUID(), "pack", "01012345678"),
                new CreditProduct("pack", "크레딧", 1100, "KRW", quantity), now.plusSeconds(60)));
        jdbc.update("update payment_orders set state='READY', provider_transaction=? where id=?",
                providerId(order), order.getId());
        return order;
    }

    private String providerId(PaymentOrder order) {
        return Long.toString(8_000_000 + order.getId());
    }

    private Map<String, String> notification(PaymentOrder order, int state, Instant date) {
        String stamp = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneId.of("Asia/Seoul")).format(date);
        return new HashMap<>(Map.of("userid", "seller", "linkkey", "test-link", "linkval", "test-value",
                "responsedate", stamp, "hash", PaymentHashes.sha256("seller" + stamp + "test-secret"),
                "var1", order.getPublicId(), "var2", order.getNotificationToken(), "mul_no", providerId(order),
                "pay_state", Integer.toString(state), "price", "1100"));
    }

    private RefundRequest request(UUID order, PaymentRefundRequest.Reason reason) {
        return new RefundRequest(UUID.randomUUID(), order, reason);
    }

    private CreditReservationReceipt reserve(String key) {
        return reservations.reserve(user, key, CreditUsage.DREAM_IMAGE_GENERATION, "image:" + key, now.plusSeconds(60));
    }

    private long accountVersion() {
        return jdbc.queryForObject("select version from credit_accounts where user_id=?", Long.class, user);
    }

    private void assertError(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }

    private HttpResponse<String> http(HttpClient client, String token, String method, String path, String body)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
