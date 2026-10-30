package com.mongle.backend.operations.payment;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import javax.sql.DataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PaymentInspectorContract {
    static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    DataSource source;
    JdbcTemplate jdbc;
    PaymentInspector inspector;
    Long user;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    void database() {
        source = dataSource();
        jdbc = new JdbcTemplate(source);
        inspector = new PaymentInspector(source, CLOCK);
    }

    DataSource dataSource() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:payment-inspect;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-initial.sql")).execute(ds);
        return ds;
    }

    @BeforeEach
    void member() {
        user = createUser();
    }

    Long createUser() {
        String email = UUID.randomUUID() + "@inspection.test";
        jdbc.update(
                "insert into users(email,nickname,created_at,updated_at)"
                        + " values(?,'점검',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                email);
        return jdbc.queryForObject("select id from users where email=?", Long.class, email);
    }

    String order(String state) {
        return order(user, state);
    }

    String order(Long owner, String state) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                insert into payment_orders(public_id,user_id,request_id,input_hash,notification_token,product_id,product_name,price,currency,credits,state,payment_url,request_deadline,version,created_at,updated_at)
                values(?,?,?,'secret-input-hash','secret-notification-token','credit-pack','secret-product-name',1100,'KRW',10,?,'https://payapp.kr/?secret=private',?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                id,
                owner,
                UUID.randomUUID().toString(),
                state,
                LocalDateTime.ofInstant(NOW.plusSeconds(45), ZoneOffset.UTC));
        return id;
    }

    long id(String order) {
        return jdbc.queryForObject(
                "select id from payment_orders where public_id=?", Long.class, order);
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    long grant(Long owner, String type, String key, long amount) {
        jdbc.update(
                """
                insert into credit_grants(user_id,source_type,source_key,source_hash,amount,available,reserved,consumed,version,created_at)
                values(?,?,?,?,?,?,0,0,0,CURRENT_TIMESTAMP)
                """,
                owner,
                type,
                key,
                hash(key),
                amount,
                amount);
        long grant =
                jdbc.queryForObject(
                        "select id from credit_grants where source_type=? and source_hash=?",
                        Long.class,
                        type,
                        hash(key));
        if (jdbc.queryForObject(
                        "select count(*) from credit_accounts where user_id=?", Long.class, owner)
                == 0)
            jdbc.update(
                    "insert into"
                        + " credit_accounts(user_id,available,reserved,version,created_at,updated_at)"
                        + " values(?,0,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    owner);
        jdbc.update(
                "update credit_accounts set available=available+? where user_id=?", amount, owner);
        ledger(owner, "GRANT", grant, null, type, amount, 0);
        return grant;
    }

    long paid(String order) {
        long owner =
                jdbc.queryForObject(
                        "select user_id from payment_orders where public_id=?", Long.class, order);
        long g = grant(owner, "PAYMENT", "payapp:" + order, 10);
        jdbc.update(
                "update payment_orders set state='PAID',credit_grant_id=? where public_id=?",
                g,
                order);
        notification(order, 4, 1100);
        return g;
    }

    void ledger(
            long owner,
            String kind,
            Long grant,
            Long reservation,
            String type,
            long available,
            long reserved) {
        var account =
                jdbc.queryForMap(
                        "select available,reserved from credit_accounts where user_id=?", owner);
        jdbc.update(
                """
                insert into credit_ledger(user_id,entry_key,kind,source_type,grant_id,reservation_id,available_delta,reserved_delta,available_after,reserved_after,created_at)
                values(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """,
                owner,
                kind + ":" + (grant == null ? reservation : grant),
                kind,
                type,
                grant,
                reservation,
                available,
                reserved,
                account.get("available"),
                account.get("reserved"));
    }

    void notification(String order, int state, long amount) {
        jdbc.update(
                "insert into"
                    + " payment_notifications(event_hash,order_id,provider_transaction,state_code,amount,responded_at,created_at)"
                    + " values(?,?,'secret-provider-transaction',?,?,?,CURRENT_TIMESTAMP)",
                hash(UUID.randomUUID().toString()),
                id(order),
                state,
                amount,
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    void hold(String order, long grant) {
        jdbc.update("update payment_orders set state='PAYMENT_REVIEW' where public_id=?", order);
        jdbc.update(
                "insert into credit_payment_holds(grant_id,user_id,created_at)"
                        + " values(?,?,CURRENT_TIMESTAMP)",
                grant,
                user);
        ledger(user, "PAYMENT_HOLD", grant, null, "PAYMENT", 0, 0);
    }

    long reserve(Map<Long, Long> portions, boolean expired) {
        long amount = portions.values().stream().mapToLong(Long::longValue).sum();
        String key = UUID.randomUUID().toString();
        jdbc.update(
                """
                insert into credit_reservations(user_id,request_key,request_hash,`usage`,target_key,target_hash,amount,state,expires_at,version,created_at,updated_at)
                values(?,?,?,'DREAM_IMAGE_GENERATION',?,?,?,'RESERVED',?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,
                user,
                key,
                hash(key),
                key,
                hash(key),
                amount,
                LocalDateTime.ofInstant(NOW.plusSeconds(expired ? -60 : 60), ZoneOffset.UTC));
        long reservation =
                jdbc.queryForObject(
                        "select id from credit_reservations where user_id=? and request_hash=?",
                        Long.class,
                        user,
                        hash(key));
        for (var portion : portions.entrySet()) {
            jdbc.update(
                    "update credit_grants set available=available-?,reserved=reserved+? where id=?",
                    portion.getValue(),
                    portion.getValue(),
                    portion.getKey());
            jdbc.update(
                    "insert into credit_allocations(reservation_id,grant_id,amount,created_at)"
                            + " values(?,?,?,CURRENT_TIMESTAMP)",
                    reservation,
                    portion.getKey(),
                    portion.getValue());
        }
        jdbc.update(
                "update credit_accounts set available=available-?,reserved=reserved+? where"
                        + " user_id=?",
                amount,
                amount,
                user);
        ledger(user, "RESERVE", null, reservation, null, -amount, amount);
        return reservation;
    }

    void finishReservation(long reservation, boolean settle) {
        var parts =
                jdbc.queryForList(
                        "select grant_id,amount from credit_allocations where reservation_id=?",
                        reservation);
        long total = 0;
        for (var row : parts) {
            long amount = ((Number) row.get("amount")).longValue();
            total += amount;
            jdbc.update(
                    settle
                            ? "update credit_grants set reserved=reserved-?,consumed=consumed+?"
                                    + " where id=?"
                            : "update credit_grants set reserved=reserved-?,available=available+?"
                                    + " where id=?",
                    amount,
                    amount,
                    row.get("grant_id"));
        }
        jdbc.update(
                "update credit_reservations set state=? where id=?",
                settle ? "SETTLED" : "RELEASED",
                reservation);
        jdbc.update(
                "update credit_accounts set reserved=reserved-?,available=available+? where"
                        + " user_id=?",
                total,
                settle ? 0 : total,
                user);
        ledger(
                user,
                settle ? "SETTLE" : "RELEASE",
                null,
                reservation,
                null,
                settle ? 0 : total,
                -total);
    }

    List<Object> snapshot() {
        var rows = new ArrayList<Object>();
        for (String table :
                List.of(
                        "payment_orders",
                        "payment_notifications",
                        "payment_transaction_bindings",
                        "credit_accounts",
                        "credit_grants",
                        "credit_allocations",
                        "credit_reservations",
                        "credit_ledger",
                        "credit_payment_holds"))
            rows.add(jdbc.queryForList("select * from " + table));
        return rows;
    }

    JsonNode inspect(String id) throws Exception {
        var before = snapshot();
        String raw = json.writeValueAsString(inspector.inspect(id));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(raw)
                .doesNotContain(
                        "secret-input-hash",
                        "secret-notification-token",
                        "secret-product-name",
                        "payapp.kr",
                        "secret-provider-transaction",
                        "source_hash",
                        "source_key",
                        "notification_token",
                        "payment_url",
                        "request_hash",
                        "target_key");
        return json.readTree(raw);
    }

    void matched(JsonNode report, String status) {
        assertThat(report.path("scope").asString()).isEqualTo("INTERNAL_DATABASE_ONLY");
        assertThat(report.path("status").asString()).isEqualTo(status);
        assertThat(report.path("findings").isEmpty()).isTrue();
    }

    @Test
    void unconfirmedWithoutCreditsDoesNotCreateAnAccountOrGrant() throws Exception {
        var report = inspect(order("READY"));
        matched(report, "UNCONFIRMED");
        assertThat(report.path("purchase").isNull()).isTrue();
        assertThat(report.at("/account/present").asBoolean()).isFalse();
        assertThat(report.at("/account/available").asLong()).isZero();
    }

    @Test
    void paidPurchaseIsSeparatedFromOtherPurchasesAndPromotionBalances() throws Exception {
        grant(user, "PROMOTION", "private-promotion-source:" + user, 3);
        String target = order("READY");
        long g = paid(target);
        paid(order("READY"));
        var report = inspect(target);
        matched(report, "CONSISTENT");
        assertThat(report.at("/purchase/grantId").asLong()).isEqualTo(g);
        assertThat(report.at("/purchase/available").asLong()).isEqualTo(10);
        assertThat(report.at("/account/available").asLong()).isEqualTo(23);
        assertThat(report.at("/account/spendingBlocked").asBoolean()).isFalse();
        assertThat(report.at("/purchaseLedger/grantedCredits").asLong()).isEqualTo(10);
    }

    @Test
    void mixedSourceReservationsAttributeOnlyTheirAllocationToThePurchase() throws Exception {
        long promotion = grant(user, "PROMOTION", "private-promotion-source:" + user, 3);
        String target = order("READY");
        long purchase = paid(target);
        long reservation = reserve(Map.of(promotion, 3L, purchase, 2L), false);
        var pending = inspect(target);
        matched(pending, "CONSISTENT");
        assertThat(pending.at("/purchase/reserved").asLong()).isEqualTo(2);
        assertThat(pending.at("/account/reserved").asLong()).isEqualTo(5);
        finishReservation(reservation, true);
        var settled = inspect(target);
        matched(settled, "CONSISTENT");
        assertThat(settled.at("/purchase/consumed").asLong()).isEqualTo(2);
        assertThat(settled.at("/purchaseAllocations/consumed").asLong()).isEqualTo(2);
        assertThat(settled.at("/purchase/available").asLong()).isEqualTo(8);
    }

    @Test
    void expiredReservationIsReportedButNotReleasedByInspection() throws Exception {
        String target = order("READY");
        long g = paid(target);
        long reservation = reserve(Map.of(g, 3L), true);
        var pending = inspect(target);
        matched(pending, "CONSISTENT");
        assertThat(pending.path("notices").valueStream().map(JsonNode::asString).toList())
                .contains("EXPIRED_RESERVATION_NOT_RELEASED");
        assertThat(pending.at("/purchaseAllocations/expiredReserved").asLong()).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "select state from credit_reservations where id=?",
                                String.class,
                                reservation))
                .isEqualTo("RESERVED");
        finishReservation(reservation, false);
        var released = inspect(target);
        matched(released, "CONSISTENT");
        assertThat(released.at("/purchaseAllocations/released").asLong()).isEqualTo(3);
        assertThat(released.at("/purchase/available").asLong()).isEqualTo(10);
    }

    @Test
    void cancellationWithConsumedCreditsAndRepeatedNotificationsKeepsHoldAndQuantities()
            throws Exception {
        String target = order("READY");
        long g = paid(target);
        long reservation = reserve(Map.of(g, 3L), false);
        finishReservation(reservation, true);
        notification(target, 70, 550);
        notification(target, 70, 550);
        hold(target, g);
        var report = inspect(target);
        matched(report, "REVIEW_REQUIRED");
        assertThat(report.at("/purchase/paymentHold").asBoolean()).isTrue();
        assertThat(report.at("/purchase/available").asLong()).isEqualTo(7);
        assertThat(report.at("/purchase/consumed").asLong()).isEqualTo(3);
        assertThat(report.path("notifications").size()).isEqualTo(2);
        assertThat(report.toString()).doesNotContain("refundAmount", "refunded");
    }

    @Test
    void otherOrderHoldIsDistinguishedAndUnpaidCancellationDoesNotInventCredits() throws Exception {
        String target = order("READY");
        paid(target);
        String other = order("READY");
        long g = paid(other);
        notification(other, 9, 1100);
        hold(other, g);
        var report = inspect(target);
        matched(report, "CONSISTENT");
        assertThat(report.at("/purchase/paymentHold").asBoolean()).isFalse();
        assertThat(report.at("/account/holdCount").asLong()).isEqualTo(1);
        assertThat(report.at("/account/spendingBlocked").asBoolean()).isTrue();
        assertThat(report.path("notices").valueStream().map(JsonNode::asString).toList())
                .contains("ACCOUNT_HAS_OTHER_PAYMENT_HOLDS");
        String cancelled = order("CANCELLED");
        notification(cancelled, 8, 1100);
        var unpaid = inspect(cancelled);
        matched(unpaid, "CONSISTENT");
        assertThat(unpaid.path("purchase").isNull()).isTrue();
    }

    @Test
    void staleRequestsAndUnknownStatesAreObservationsWithoutStateUpdates() throws Exception {
        String target = order("REQUESTING");
        jdbc.update(
                "update payment_orders set request_deadline=? where public_id=?",
                LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC),
                target);
        var report = inspect(target);
        matched(report, "REVIEW_REQUIRED");
        assertThat(report.at("/order/storedState").asString()).isEqualTo("REQUESTING");
        assertThat(report.at("/order/effectiveState").asString()).isEqualTo("UNKNOWN");
        jdbc.update(
                "update payment_orders set state='secret-invalid-state' where public_id=?", target);
        var invalid = inspect(target);
        assertThat(invalid.toString()).doesNotContain("secret-invalid-state");
        assertThat(invalid.path("findings").valueStream().map(JsonNode::asString).toList())
                .contains("UNKNOWN_ORDER_STATE");
    }

    @Test
    void detectsLinksOwnershipSourceAndBalanceInconsistenciesWithoutRepairing() throws Exception {
        String target = order("READY");
        long g = paid(target);
        jdbc.update("update payment_orders set credit_grant_id=null where public_id=?", target);
        assertFinding(inspect(target), "ORDER_GRANT_LINK_MISMATCH");
        jdbc.update("update payment_orders set credit_grant_id=? where public_id=?", g, target);
        jdbc.update("update credit_accounts set available=available+1 where user_id=?", user);
        assertFinding(inspect(target), "ACCOUNT_GRANT_BALANCE_MISMATCH");
        assertFinding(inspect(target), "ACCOUNT_LEDGER_BALANCE_MISMATCH");
        jdbc.update("update credit_accounts set available=available-1 where user_id=?", user);
        jdbc.update("update credit_grants set consumed=1 where id=?", g);
        assertFinding(inspect(target), "PURCHASE_CREDIT_TOTAL_MISMATCH");
        assertFinding(inspect(target), "PURCHASE_ALLOCATION_MISMATCH");
        jdbc.update(
                "update credit_grants set consumed=0,source_hash=? where id=?", "f".repeat(64), g);
        assertFinding(inspect(target), "GRANT_SOURCE_MISMATCH");
        jdbc.update(
                "update credit_grants set source_hash=? where id=?", hash("payapp:" + target), g);
        Long other = createUser();
        String foreign = order(other, "READY");
        long foreignGrant = paid(foreign);
        jdbc.update(
                "update payment_orders set credit_grant_id=? where public_id=?",
                foreignGrant,
                target);
        var report = inspect(target);
        assertFinding(report, "GRANT_OWNER_MISMATCH");
        assertThat(report.at("/purchase/grantId").asLong()).isEqualTo(g);
    }

    @Test
    void detectsMissingCompletionGrantLedgerAndCancellationHold() throws Exception {
        String target = order("PAID");
        assertFinding(inspect(target), "PAID_WITHOUT_MATCHING_GRANT");
        long g = paid(target);
        jdbc.update("delete from payment_notifications where order_id=?", id(target));
        assertFinding(inspect(target), "COMPLETION_NOTIFICATION_MISSING");
        notification(target, 4, 1100);
        notification(target, 9, 1100);
        assertFinding(inspect(target), "CANCELLATION_HOLD_MISSING");
        jdbc.update("delete from credit_ledger where grant_id=?", g);
        assertFinding(inspect(target), "PURCHASE_GRANT_LEDGER_MISMATCH");
    }

    @Test
    void detectsUnlinkedForeignSourceAndPaidStateWithAnActiveHold() throws Exception {
        String pending = order("READY");
        grant(createUser(), "PAYMENT", "payapp:" + pending, 10);
        var foreign = inspect(pending);
        assertFinding(foreign, "GRANT_OWNER_MISMATCH");
        assertThat(foreign.path("purchase").isNull()).isTrue();
        String target = order("READY");
        long g = paid(target);
        hold(target, g);
        jdbc.update("update payment_orders set state='PAID' where public_id=?", target);
        var held = inspect(target);
        assertFinding(held, "HOLD_WITHOUT_REVIEW_STATE");
        assertThat(held.path("status").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(held.at("/account/spendingBlocked").asBoolean()).isTrue();
    }

    @Test
    void detectsUnknownAndWrongAmountNotificationsWithoutInferringRefunds() throws Exception {
        String target = order("READY");
        paid(target);
        notification(target, 4, 1000);
        notification(target, 70, 1101);
        notification(target, 777, 1100);
        var report = inspect(target);
        assertFinding(report, "NOTIFICATION_AMOUNT_MISMATCH");
        assertFinding(report, "UNKNOWN_NOTIFICATION_STATE");
        assertThat(
                        report.path("notifications")
                                .valueStream()
                                .map(item -> item.path("stateCode").asInt())
                                .toList())
                .contains(-1);
        assertThat(report.toString()).doesNotContain("refundAmount");
    }

    @Test
    void absentOrderReturnsSpecificFailureWithoutAnyDataCreation() {
        var before = snapshot();
        assertThatThrownBy(() -> inspector.inspect(UUID.randomUUID().toString()))
                .isInstanceOf(PaymentInspectCommand.Failure.class)
                .hasMessage("ORDER_NOT_FOUND");
        assertThat(snapshot()).isEqualTo(before);
    }

    void assertFinding(JsonNode report, String code) {
        assertThat(report.path("status").asString()).isEqualTo("REVIEW_REQUIRED");
        assertThat(report.path("findings").valueStream().map(JsonNode::asString).toList())
                .contains(code);
    }
}
