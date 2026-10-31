package com.mongle.backend.operations.payment;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import javax.sql.DataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PaymentReclaimerContract {
    PaymentInspectorContract f;
    DataSource source;
    JdbcTemplate jdbc;
    PaymentReclaimer engine;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    void database() {
        source = dataSource();
        f =
                new PaymentInspectorContract() {
                    @Override
                    DataSource dataSource() {
                        return source;
                    }
                };
        // Use the outer datasource rather than this anonymous fixture's uninitialized field.
        f.source = source;
        f.jdbc = new JdbcTemplate(source);
        f.inspector = new PaymentInspector(source, PaymentInspectorContract.CLOCK);
        jdbc = f.jdbc;
        engine = new PaymentReclaimer(source, PaymentInspectorContract.CLOCK);
    }

    DataSource dataSource() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:reclamations;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        new ResourceDatabasePopulator(
                        new ClassPathResource("db/schema-initial.sql"),
                        new ClassPathResource("db/migration/V6__payment_credit_reclamations.sql"))
                .execute(ds);
        return ds;
    }

    @BeforeEach
    void member() {
        f.member();
    }

    String cancelled() {
        String o = f.order("READY");
        long g = f.paid(o);
        String transaction = Long.toString(f.id(o));
        jdbc.update(
                "update payment_orders set provider_transaction=? where public_id=?",
                transaction,
                o);
        jdbc.update(
                "update payment_notifications set provider_transaction=?,responded_at=? where"
                        + " order_id=?",
                transaction,
                LocalDateTime.ofInstant(
                        PaymentInspectorContract.NOW.minusSeconds(30), ZoneOffset.UTC),
                f.id(o));
        jdbc.update(
                "insert into payment_transaction_bindings(transaction_id,order_id) values(?,?)",
                transaction,
                f.id(o));
        notify(o, 9, 1100, PaymentInspectorContract.NOW);
        f.hold(o, g);
        return o;
    }

    void notify(String o, int state, long amount, Instant time) {
        f.notification(o, state, amount);
        jdbc.update(
                "update payment_notifications set provider_transaction=?,responded_at=? where"
                        + " order_id=? and state_code=?",
                Long.toString(f.id(o)),
                LocalDateTime.ofInstant(time, ZoneOffset.UTC),
                f.id(o),
                state);
    }

    PaymentReclaimRequest request(String mode, String o, String key, String token) {
        return new PaymentReclaimRequest(mode, o, key, "case-119", token);
    }

    JsonNode preview(String o, String key) throws Exception {
        var before = f.snapshot();
        var value = json.valueToTree(engine.execute(request("preview", o, key, null)));
        assertThat(f.snapshot()).isEqualTo(before);
        return value;
    }

    JsonNode apply(String o, String key, String token) throws Exception {
        return json.valueToTree(engine.execute(request("apply", o, key, token)));
    }

    long grant(String o) {
        return jdbc.queryForObject(
                "select credit_grant_id from payment_orders where public_id=?", Long.class, o);
    }

    void refused(String o) throws Exception {
        var before = f.snapshot();
        var plan = preview(o, "refuse-" + o);
        assertThat(plan.path("status").asString()).isEqualTo("REFUSED");
        assertThatThrownBy(() -> apply(o, "refuse-" + o, plan.path("snapshot").asString()))
                .hasMessage("RECLAMATION_NOT_ALLOWED");
        assertThat(f.snapshot()).isEqualTo(before);
    }

    @Test
    void unusedWholePurchaseIsReclaimedAtomicallyWithoutTouchingPromotionOrOriginalAmount()
            throws Exception {
        String o = cancelled();
        f.grant(f.user, "PROMOTION", "promo:" + o, 3);
        var plan = preview(o, "whole-" + o);
        assertThat(plan.path("status").asString()).isEqualTo("WOULD_RECLAIM");
        assertThat(plan.path("expectedAvailableAfter").asLong()).isEqualTo(3);
        assertThat(plan.path("remainingHoldCount").asLong()).isZero();
        var result = apply(o, "whole-" + o, plan.path("snapshot").asString());
        assertThat(result.path("status").asString()).isEqualTo("RECLAIMED");
        assertThat(result.at("/inspection/status").asString()).isEqualTo("CONSISTENT");
        assertThat(result.at("/inspection/account/available").asLong()).isEqualTo(3);
        assertThat(result.at("/inspection/account/spendingBlocked").asBoolean()).isFalse();
        assertThat(result.at("/inspection/purchase/amount").asLong()).isEqualTo(10);
        assertThat(result.at("/inspection/purchase/consumed").asLong()).isZero();
        assertThat(result.at("/inspection/purchase/reclaimed").asLong()).isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "select available_delta from credit_ledger where grant_id=? and"
                                        + " kind='RECLAIM'",
                                Long.class,
                                grant(o)))
                .isEqualTo(-10);
        assertThat(
                        jdbc.queryForObject(
                                "select operator_reference from payment_credit_reclamations where"
                                        + " order_id=?",
                                String.class,
                                f.id(o)))
                .isEqualTo("case-119");
        assertThat(result.toString())
                .doesNotContain(
                        "secret-", "provider_transaction", "request_key", "operator_reference");
    }

    @Test
    void otherPurchaseHoldAndMixedSourceActiveReservationArePreserved() throws Exception {
        String o = cancelled();
        String other = cancelled();
        var plan = preview(o, "other-" + o);
        var result = apply(o, "other-" + o, plan.path("snapshot").asString());
        assertThat(result.path("remainingHoldCount").asLong()).isEqualTo(1);
        assertThat(result.at("/inspection/account/spendingBlocked").asBoolean()).isTrue();
        assertThat(f.inspect(other).at("/purchase/available").asLong()).isEqualTo(10);
        String target = cancelled();
        long promo = f.grant(f.user, "PROMOTION", "reserve-promo:" + target, 3);
        long reservation = f.reserve(Map.of(promo, 3L, grant(target), 2L), false);
        refused(target);
        f.finishReservation(reservation, true);
        refused(target);
    }

    @Test
    void fullReleasedReservationAllowsReclamationButExpiredActiveReservationDoesNot()
            throws Exception {
        String o = cancelled();
        long reservation = f.reserve(Map.of(grant(o), 3L), true);
        refused(o);
        f.finishReservation(reservation, false);
        assertThat(preview(o, "released-" + o).path("status").asString())
                .isEqualTo("WOULD_RECLAIM");
    }

    @Test
    void partialRequestOnlyUnknownLateCompletionAndWrongAmountAreRefused() throws Exception {
        String partial = cancelled();
        notify(partial, 70, 550, PaymentInspectorContract.NOW);
        refused(partial);
        String request = cancelled();
        jdbc.update(
                "update payment_notifications set state_code=8 where order_id=? and state_code=9",
                f.id(request));
        refused(request);
        String unknown = cancelled();
        jdbc.update("update payment_orders set state='UNKNOWN' where public_id=?", unknown);
        refused(unknown);
        String late = cancelled();
        notify(late, 4, 1100, PaymentInspectorContract.NOW.plusSeconds(10));
        refused(late);
        String wrong = cancelled();
        jdbc.update(
                "update payment_notifications set amount=1000 where order_id=? and state_code=9",
                f.id(wrong));
        refused(wrong);
    }

    @Test
    void ownershipBindingHoldAndAccountInconsistenciesAreRefused() throws Exception {
        String o = cancelled();
        jdbc.update("delete from payment_transaction_bindings where order_id=?", f.id(o));
        refused(o);
        String noHold = cancelled();
        jdbc.update("delete from credit_payment_holds where grant_id=?", grant(noHold));
        refused(noHold);
        String bad = cancelled();
        jdbc.update("update credit_accounts set available=available+1 where user_id=?", f.user);
        refused(bad);
    }

    @Test
    void changedVersionOrEvidenceAfterPreviewNeverAppliesStalePlan() throws Exception {
        String o = cancelled();
        String key = "stale-" + o;
        var plan = preview(o, key);
        jdbc.update("update credit_accounts set version=version+1 where user_id=?", f.user);
        var before = f.snapshot();
        assertThatThrownBy(() -> apply(o, key, plan.path("snapshot").asString()))
                .hasMessage("SNAPSHOT_CHANGED");
        assertThat(f.snapshot()).isEqualTo(before);
        var newer = preview(o, key);
        notify(o, 64, 1100, PaymentInspectorContract.NOW);
        before = f.snapshot();
        assertThatThrownBy(() -> apply(o, key, newer.path("snapshot").asString()))
                .hasMessage("SNAPSHOT_CHANGED");
        assertThat(f.snapshot()).isEqualTo(before);
    }

    @Test
    void replayAndConcurrentApplyReclaimOnceAndRejectChangedRequestIdentity() throws Exception {
        String o = cancelled();
        String key = "parallel-" + o;
        String token = preview(o, key).path("snapshot").asString();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return apply(o, key, token).path("status").asString();
                                }));
            start.countDown();
            assertThat(
                            List.of(
                                    futures.get(0).get(10, TimeUnit.SECONDS),
                                    futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("RECLAIMED", "ALREADY_RECLAIMED");
        }
        var before = f.snapshot();
        assertThat(apply(o, key, token).path("status").asString()).isEqualTo("ALREADY_RECLAIMED");
        assertThat(preview(o, key).path("status").asString()).isEqualTo("ALREADY_RECLAIMED");
        assertThatThrownBy(() -> apply(o, "other-key", token)).hasMessage("REQUEST_CONFLICT");
        assertThatThrownBy(
                        () ->
                                engine.execute(
                                        new PaymentReclaimRequest(
                                                "apply", o, key, "different-case", token)))
                .hasMessage("REQUEST_CONFLICT");
        assertThat(f.snapshot()).isEqualTo(before);
        String another = cancelled();
        assertThatThrownBy(() -> preview(another, key)).hasMessage("REQUEST_CONFLICT");
        jdbc.update("update payment_credit_reclamations set amount=9 where order_id=?", f.id(o));
        var corrupt = f.snapshot();
        assertThatThrownBy(() -> apply(o, key, token))
                .hasMessage("RECLAMATION_RECORD_INCONSISTENT");
        assertThat(f.snapshot()).isEqualTo(corrupt);
    }

    @Test
    void ledgerFailureRollsBackGrantAccountOrderHistoryAndHoldTogether() throws Exception {
        String o = cancelled();
        String key = "rollback-" + o;
        String token = preview(o, key).path("snapshot").asString();
        DataSource failing =
                new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        var real = source.getConnection();
                        return (Connection)
                                Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {Connection.class},
                                        (proxy, method, args) -> {
                                            try {
                                                if (method.getName().equals("prepareStatement")
                                                        && ((String) args[0])
                                                                .contains(
                                                                        "insert into"
                                                                                + " credit_ledger"))
                                                    throw new SQLException("private-ledger-error");
                                                return method.invoke(real, args);
                                            } catch (InvocationTargetException e) {
                                                throw e.getCause();
                                            }
                                        });
                    }
                };
        var before = f.snapshot();
        assertThatThrownBy(
                        () ->
                                new PaymentReclaimer(failing, PaymentInspectorContract.CLOCK)
                                        .execute(request("apply", o, key, token)))
                .isInstanceOf(SQLException.class);
        assertThat(f.snapshot()).isEqualTo(before);
    }
}
