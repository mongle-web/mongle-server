package com.mongle.backend.operations.credit;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.credit.*;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.error.*;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.*;
import java.util.concurrent.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestCreditContextIntegrationTest {
    TestCreditContext context;
    JdbcTemplate jdbc;
    javax.sql.DataSource source;
    Long user;

    @BeforeAll
    void database() {
        source = dataSource();
        jdbc = new JdbcTemplate(source);
        context = TestCreditContext.open(source, properties(true));
    }

    javax.sql.DataSource dataSource() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:operator-credits;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-initial.sql"))
                .execute(source);
        return source;
    }

    @AfterAll
    void close() {
        if (context != null) context.close();
    }

    @BeforeEach
    void account() {
        user = user("테스트");
    }

    static CreditProperties properties(boolean enabled) {
        return new CreditProperties(
                enabled, CreditProperties.ExpiryPolicy.NO_EXPIRY, null, 3L, 1L, 3L, 1L);
    }

    Long user(String nickname) {
        String email = UUID.randomUUID() + "@test.invalid";
        jdbc.update(
                "insert into users(email,nickname,created_at,updated_at)"
                        + " values(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                email,
                nickname);
        return jdbc.queryForObject("select id from users where email=?", Long.class, email);
    }

    TestCreditRequest request(String operation, long amount, String key) {
        return new TestCreditRequest(operation, user, amount, key);
    }

    @Test
    void previewDoesNotCreateAccountGrantOrLedgerAndGrantIsAtomicAndIdempotent() {
        String key = UUID.randomUUID().toString();
        var preview = context.execute(request("preview", 100, key));
        assertThat(preview.alreadyGranted()).isFalse();
        assertThat(preview.grantId()).isNull();
        assertThat(preview.balance().total()).isZero();
        for (String table : List.of("credit_accounts", "credit_grants", "credit_ledger"))
            assertThat(count(table)).isZero();
        var granted = context.execute(request("grant", 100, key));
        assertThat(granted.alreadyGranted()).isFalse();
        assertThat(granted.balance().available()).isEqualTo(100);
        assertThat(context.execute(request("grant", 100, key)).alreadyGranted()).isTrue();
        assertThat(context.execute(request("preview", 100, key)).grantId())
                .isEqualTo(granted.grantId());
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
        assertThat(
                        jdbc.queryForMap(
                                "select source_type,source_key,amount,available,reserved,consumed"
                                        + " from credit_grants where id=?",
                                granted.grantId()))
                .containsEntry("source_type", "PROMOTION")
                .containsEntry("source_key", "test-credit:" + key)
                .containsEntry("amount", 100L)
                .containsEntry("available", 100L)
                .containsEntry("reserved", 0L)
                .containsEntry("consumed", 0L);
        assertThat(
                        jdbc.queryForMap(
                                "select"
                                    + " kind,source_type,available_delta,reserved_delta,available_after"
                                    + " from credit_ledger where grant_id=?",
                                granted.grantId()))
                .containsEntry("kind", "GRANT")
                .containsEntry("source_type", "PROMOTION")
                .containsEntry("available_delta", 100L)
                .containsEntry("reserved_delta", 0L)
                .containsEntry("available_after", 100L);
    }

    @Test
    void sameKeyCannotBeReusedForDifferentOwnerOrAmountAndAnotherKeyCanTopUp() {
        String key = UUID.randomUUID().toString();
        context.execute(request("grant", 100, key));
        error(() -> context.execute(request("grant", 99, key)), CreditErrorCode.REQUEST_CONFLICT);
        Long other = user("다른계정");
        error(
                () -> context.execute(new TestCreditRequest("grant", other, 100, key)),
                CreditErrorCode.REQUEST_CONFLICT);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from credit_accounts where user_id=?",
                                Long.class,
                                other))
                .isZero();
        assertThat(
                        context.execute(request("grant", 25, UUID.randomUUID().toString()))
                                .balance()
                                .available())
                .isEqualTo(125);
        assertThat(count("credit_ledger")).isEqualTo(2);
    }

    @Test
    void sameRequestAcrossConcurrentContextsIsGrantedOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<PromotionCreditOperations.Result>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return context.execute(request("grant", 100, key));
                                }));
            go.countDown();
            var results = new ArrayList<PromotionCreditOperations.Result>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> !r.alreadyGranted()).count()).isOne();
            assertThat(results).allSatisfy(r -> assertThat(r.balance().total()).isEqualTo(100));
        }
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    @Test
    void failedLedgerWriteRollsBackAccountAndGrant() {
        ddl(
                "alter table credit_ledger add constraint operator_test_reject check"
                        + " (available_delta<>101)");
        try {
            assertThatThrownBy(
                            () ->
                                    context.execute(
                                            request("grant", 101, UUID.randomUUID().toString())))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            for (String table : List.of("credit_accounts", "credit_grants", "credit_ledger"))
                assertThat(count(table)).isZero();
        } finally {
            ddl("alter table credit_ledger drop constraint operator_test_reject");
        }
    }

    @Test
    void missingUnonboardedAndOverflowRequestsCannotChangeState() {
        error(
                () -> context.execute(new TestCreditRequest("grant", Long.MAX_VALUE, 1, "absent")),
                CreditErrorCode.NOT_FOUND);
        Long incomplete = user(null);
        error(
                () ->
                        context.execute(
                                new TestCreditRequest("grant", incomplete, 100, "incomplete")),
                UserErrorCode.ONBOARDING_REQUIRED);
        context.execute(request("grant", Long.MAX_VALUE, UUID.randomUUID().toString()));
        error(
                () -> context.execute(request("preview", 1, "overflow")),
                CreditErrorCode.CAPACITY_EXCEEDED);
        error(
                () -> context.execute(request("grant", 1, "overflow")),
                CreditErrorCode.CAPACITY_EXCEEDED);
        assertThat(count("credit_grants")).isOne();
        assertThat(count("credit_ledger")).isOne();
    }

    @Test
    void disabledCreditsAndPaymentHoldAreNeverBypassed() {
        try (var disabled = TestCreditContext.open(source, properties(false))) {
            error(
                    () -> disabled.execute(request("grant", 100, "disabled")),
                    CreditErrorCode.UNAVAILABLE);
            error(
                    () -> disabled.execute(request("preview", 100, "disabled")),
                    CreditErrorCode.UNAVAILABLE);
        }
        var first = context.execute(request("grant", 100, UUID.randomUUID().toString()));
        jdbc.update(
                "insert into credit_payment_holds(grant_id,user_id,created_at)"
                        + " values(?,?,CURRENT_TIMESTAMP)",
                first.grantId(),
                user);
        error(
                () -> context.execute(request("preview", 100, "held")),
                CreditErrorCode.PAYMENT_REVIEW);
        error(() -> context.execute(request("grant", 100, "held")), CreditErrorCode.PAYMENT_REVIEW);
        assertThat(count("credit_ledger")).isOne();
        assertThat(count("credit_payment_holds")).isOne();
    }

    void ddl(String sql) {
        jdbc.execute(sql);
    }

    long count(String table) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where user_id=?", Long.class, user);
    }

    static void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
