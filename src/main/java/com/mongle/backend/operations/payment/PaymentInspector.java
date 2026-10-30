package com.mongle.backend.operations.payment;

import com.mongle.backend.domain.payment.PaymentOrder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/** 한 주문과 같은 소유자의 내부 회계만 한 연결의 읽기 전용 스냅샷에서 대조한다. */
final class PaymentInspector {
    private final DataSource source;
    private final Clock clock;

    PaymentInspector(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    Map<String, Object> inspect(String orderId) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Connection connection;
        try {
            connection = source.getConnection();
        } catch (SQLException failure) {
            throw new PaymentInspectCommand.Failure("DB_CONNECTION_FAILED");
        }
        try (connection) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                return snapshot(new Session(connection, deadline), orderId);
            } catch (SQLTimeoutException failure) {
                throw new PaymentInspectCommand.Failure("INSPECTION_TIMEOUT");
            } finally {
                connection.rollback();
            }
        }
    }

    private Map<String, Object> snapshot(Session db, String orderId) throws SQLException {
        var rows =
                db.query(
                        """
                        select id,user_id,product_id,price,currency,credits,state,credit_grant_id,request_deadline
                        from payment_orders where public_id=?
                        """,
                        r ->
                                new Order(
                                        r.getLong("id"),
                                        r.getLong("user_id"),
                                        r.getString("product_id"),
                                        number(r, "price"),
                                        r.getString("currency"),
                                        number(r, "credits"),
                                        r.getString("state"),
                                        r.getObject("credit_grant_id") == null
                                                ? null
                                                : r.getLong("credit_grant_id"),
                                        instant(r, "request_deadline")),
                        orderId);
        if (rows.isEmpty()) throw new PaymentInspectCommand.Failure("ORDER_NOT_FOUND");
        if (rows.size() != 1) throw new PaymentInspectCommand.Failure("ORDER_DATA_INVALID");
        var order = rows.getFirst();
        Instant observed = clock.instant();
        var findings = new LinkedHashSet<String>();
        var notices = new LinkedHashSet<String>();
        Set<String> states = new HashSet<>();
        for (var state : PaymentOrder.State.values()) states.add(state.name());
        String storedState = states.contains(order.state) ? order.state : "INVALID";
        if (storedState.equals("INVALID")) findings.add("UNKNOWN_ORDER_STATE");
        String effectiveState =
                storedState.equals("REQUESTING") && !order.deadline.isAfter(observed)
                        ? "UNKNOWN"
                        : storedState;
        String product =
                order.product != null && order.product.matches("[a-z][a-z0-9-]{0,49}")
                        ? order.product
                        : null;
        if (product == null
                || !"KRW".equals(order.currency)
                || order.price.compareTo(BigInteger.valueOf(1000)) < 0
                || order.credits.signum() <= 0) findings.add("ORDER_AMOUNT_OR_PRODUCT_INVALID");

        String key = "payapp:" + orderId;
        String hash = hash(key);
        var candidates =
                db.query(
                        """
                        select id,user_id,source_type,source_key,source_hash,amount,available,reserved,consumed
                        from credit_grants where id=? or (source_type='PAYMENT' and source_hash=?) limit 3
                        """,
                        r ->
                                new Grant(
                                        r.getLong("id"),
                                        r.getLong("user_id"),
                                        "PAYMENT".equals(r.getString("source_type"))
                                                && key.equals(r.getString("source_key"))
                                                && hash.equals(r.getString("source_hash")),
                                        number(r, "amount"),
                                        number(r, "available"),
                                        number(r, "reserved"),
                                        number(r, "consumed")),
                        order.grantId,
                        hash);
        if (candidates.stream().anyMatch(g -> g.sourceMatches && g.user != order.user))
            findings.add("GRANT_OWNER_MISMATCH");
        var linked =
                candidates.stream().filter(g -> Objects.equals(g.id, order.grantId)).findFirst();
        if (order.grantId != null && linked.isEmpty()) findings.add("LINKED_GRANT_MISSING");
        linked.ifPresent(
                g -> {
                    if (g.user != order.user) findings.add("GRANT_OWNER_MISMATCH");
                    if (!g.sourceMatches) findings.add("GRANT_SOURCE_MISMATCH");
                });
        var owned =
                candidates.stream().filter(g -> g.user == order.user && g.sourceMatches).toList();
        if (owned.size() > 1) findings.add("DUPLICATE_PAYMENT_SOURCE");
        Grant grant = owned.size() == 1 ? owned.getFirst() : null;
        if (grant != null && !Objects.equals(order.grantId, grant.id))
            findings.add("ORDER_GRANT_LINK_MISMATCH");
        if (storedState.equals("PAID") && grant == null)
            findings.add("PAID_WITHOUT_MATCHING_GRANT");
        if (grant != null && !Set.of("PAID", "PAYMENT_REVIEW").contains(storedState))
            findings.add("GRANT_WITHOUT_CONFIRMED_ORDER_STATE");
        long grantId = grant == null ? -1 : grant.id;
        if (grant != null
                && (!grant.amount.equals(order.credits)
                        || !grant.amount.equals(
                                grant.available.add(grant.reserved).add(grant.consumed))))
            findings.add("PURCHASE_CREDIT_TOTAL_MISMATCH");

        var accounts =
                db.query(
                        "select available,reserved from credit_accounts where user_id=?",
                        r -> values(r, "available", "reserved"),
                        order.user);
        var account =
                accounts.isEmpty()
                        ? List.of(BigInteger.ZERO, BigInteger.ZERO)
                        : accounts.getFirst();
        var totals =
                db.query(
                                """
                                select count(*) as grants,coalesce(sum(available),0) as available,coalesce(sum(reserved),0) as reserved,
                                coalesce(sum(case when amount<>available+reserved+consumed then 1 else 0 end),0) as invalid_grants
                                from credit_grants where user_id=?
                                """,
                                r -> values(r, "grants", "available", "reserved", "invalid_grants"),
                                order.user)
                        .getFirst();
        if (!account.get(0).equals(totals.get(1))
                || !account.get(1).equals(totals.get(2))
                || totals.get(3).signum() != 0
                || accounts.isEmpty() && totals.get(0).signum() != 0)
            findings.add("ACCOUNT_GRANT_BALANCE_MISMATCH");

        var ledger =
                db.query(
                                """
                                select coalesce(sum(case when user_id=? then available_delta else 0 end),0) as available,
                                coalesce(sum(case when user_id=? then reserved_delta else 0 end),0) as reserved,
                                coalesce(sum(case when grant_id=? and kind='GRANT' then 1 else 0 end),0) as grants,
                                coalesce(sum(case when grant_id=? and kind='GRANT' then available_delta else 0 end),0) as granted,
                                coalesce(sum(case when grant_id=? and kind='GRANT' and (source_type<>'PAYMENT' or source_type is null or reserved_delta<>0) then 1 else 0 end),0) as invalid_grants,
                                coalesce(sum(case when grant_id=? and kind='PAYMENT_HOLD' then 1 else 0 end),0) as holds,
                                coalesce(sum(case when grant_id=? and user_id<>? then 1 else 0 end),0) as invalid_owner
                                from credit_ledger where user_id=? or grant_id=?
                                """,
                                r ->
                                        values(
                                                r,
                                                "available",
                                                "reserved",
                                                "grants",
                                                "granted",
                                                "invalid_grants",
                                                "holds",
                                                "invalid_owner"),
                                order.user,
                                order.user,
                                grantId,
                                grantId,
                                grantId,
                                grantId,
                                grantId,
                                order.user,
                                order.user,
                                grantId)
                        .getFirst();
        if (!account.get(0).equals(ledger.get(0)) || !account.get(1).equals(ledger.get(1)))
            findings.add("ACCOUNT_LEDGER_BALANCE_MISMATCH");
        if (grant != null
                && (!ledger.get(2).equals(BigInteger.ONE)
                        || !ledger.get(3).equals(grant.amount)
                        || ledger.get(4).signum() != 0
                        || ledger.get(6).signum() != 0))
            findings.add("PURCHASE_GRANT_LEDGER_MISMATCH");

        var holds =
                db.query(
                                """
                                select coalesce(sum(case when user_id=? then 1 else 0 end),0) as account_holds,
                                coalesce(sum(case when grant_id=? then 1 else 0 end),0) as purchase_holds,
                                coalesce(sum(case when grant_id=? and user_id<>? then 1 else 0 end),0) as invalid_owner
                                from credit_payment_holds where user_id=? or grant_id=?
                                """,
                                r -> values(r, "account_holds", "purchase_holds", "invalid_owner"),
                                order.user,
                                grantId,
                                grantId,
                                order.user,
                                order.user,
                                grantId)
                        .getFirst();
        if (holds.get(2).signum() != 0 || !holds.get(1).equals(ledger.get(5)))
            findings.add("PAYMENT_HOLD_LEDGER_MISMATCH");
        if (holds.get(1).signum() > 0 && !storedState.equals("PAYMENT_REVIEW"))
            findings.add("HOLD_WITHOUT_REVIEW_STATE");
        if (holds.get(0).compareTo(holds.get(1)) > 0)
            notices.add("ACCOUNT_HAS_OTHER_PAYMENT_HOLDS");

        var allocations =
                db.query(
                                """
                                select coalesce(sum(case when r.state='RESERVED' then a.amount else 0 end),0) as reserved,
                                coalesce(sum(case when r.state='SETTLED' then a.amount else 0 end),0) as consumed,
                                coalesce(sum(case when r.state='RELEASED' then a.amount else 0 end),0) as released,
                                coalesce(sum(case when r.state='RESERVED' and r.expires_at<=? then a.amount else 0 end),0) as expired,
                                coalesce(sum(case when r.id is null or r.user_id<>? or a.amount<=0 or a.amount>r.amount or r.state not in ('RESERVED','SETTLED','RELEASED') then 1 else 0 end),0) as invalid_allocations
                                from credit_allocations a left join credit_reservations r on r.id=a.reservation_id where a.grant_id=?
                                """,
                                r ->
                                        values(
                                                r,
                                                "reserved",
                                                "consumed",
                                                "released",
                                                "expired",
                                                "invalid_allocations"),
                                Timestamp.from(observed),
                                order.user,
                                grantId)
                        .getFirst();
        if (grant != null
                && (!allocations.get(0).equals(grant.reserved)
                        || !allocations.get(1).equals(grant.consumed)
                        || allocations.get(4).signum() != 0))
            findings.add("PURCHASE_ALLOCATION_MISMATCH");
        if (allocations.get(3).signum() > 0) notices.add("EXPIRED_RESERVATION_NOT_RELEASED");

        var notifications =
                db.query(
                        """
                        select case when state_code in (1,4,8,9,10,32,64,70,71) then state_code else -1 end as state_code,
                        count(*) as notifications,min(amount) as minimum_amount,max(amount) as maximum_amount
                        from payment_notifications where order_id=?
                        group by case when state_code in (1,4,8,9,10,32,64,70,71) then state_code else -1 end order by state_code
                        """,
                        r ->
                                new Notice(
                                        r.getInt("state_code"),
                                        number(r, "notifications"),
                                        number(r, "minimum_amount"),
                                        number(r, "maximum_amount")),
                        order.id);
        BigInteger completions = BigInteger.ZERO, cancellations = BigInteger.ZERO;
        for (var notice : notifications) {
            if (notice.stateCode == 4) completions = completions.add(notice.count);
            if (Set.of(8, 9, 32, 64, 70, 71).contains(notice.stateCode))
                cancellations = cancellations.add(notice.count);
            if (notice.stateCode == -1) findings.add("UNKNOWN_NOTIFICATION_STATE");
            else if (Set.of(70, 71).contains(notice.stateCode)
                    ? notice.minimum.signum() <= 0 || notice.maximum.compareTo(order.price) > 0
                    : !notice.minimum.equals(order.price) || !notice.maximum.equals(order.price))
                findings.add("NOTIFICATION_AMOUNT_MISMATCH");
        }
        if (storedState.equals("PAID") && completions.signum() == 0)
            findings.add("COMPLETION_NOTIFICATION_MISSING");
        if (grant != null && cancellations.signum() > 0 && holds.get(1).signum() == 0)
            findings.add("CANCELLATION_HOLD_MISSING");
        String status =
                !findings.isEmpty()
                                || Set.of("UNKNOWN", "PAYMENT_REVIEW", "INVALID")
                                        .contains(effectiveState)
                        ? "REVIEW_REQUIRED"
                        : Set.of("REQUESTING", "READY", "PENDING").contains(effectiveState)
                                ? "UNCONFIRMED"
                                : "CONSISTENT";
        var result = new LinkedHashMap<String, Object>();
        result.put("event", "payment-inspection-completed");
        result.put("scope", "INTERNAL_DATABASE_ONLY");
        result.put("status", status);
        result.put("observedAt", observed);
        result.put(
                "order",
                map(
                        "orderId",
                        orderId,
                        "userId",
                        order.user,
                        "productId",
                        product,
                        "price",
                        order.price,
                        "currency",
                        "KRW".equals(order.currency) ? "KRW" : null,
                        "credits",
                        order.credits,
                        "storedState",
                        storedState,
                        "effectiveState",
                        effectiveState,
                        "linkedGrantId",
                        order.grantId));
        result.put(
                "purchase",
                grant == null
                        ? null
                        : map(
                                "grantId",
                                grant.id,
                                "amount",
                                grant.amount,
                                "available",
                                grant.available,
                                "reserved",
                                grant.reserved,
                                "consumed",
                                grant.consumed,
                                "paymentHold",
                                holds.get(1).signum() > 0));
        result.put(
                "account",
                map(
                        "present",
                        !accounts.isEmpty(),
                        "available",
                        account.get(0),
                        "reserved",
                        account.get(1),
                        "holdCount",
                        holds.get(0),
                        "spendingBlocked",
                        holds.get(0).signum() > 0,
                        "grantAvailable",
                        totals.get(1),
                        "grantReserved",
                        totals.get(2),
                        "ledgerAvailable",
                        ledger.get(0),
                        "ledgerReserved",
                        ledger.get(1)));
        result.put(
                "purchaseLedger",
                map(
                        "grantEntryCount",
                        ledger.get(2),
                        "grantedCredits",
                        ledger.get(3),
                        "holdEntryCount",
                        ledger.get(5)));
        result.put(
                "purchaseAllocations",
                map(
                        "reserved",
                        allocations.get(0),
                        "consumed",
                        allocations.get(1),
                        "released",
                        allocations.get(2),
                        "expiredReserved",
                        allocations.get(3)));
        result.put(
                "notifications",
                notifications.stream()
                        .map(
                                n ->
                                        map(
                                                "stateCode",
                                                n.stateCode,
                                                "count",
                                                n.count,
                                                "minimumAmount",
                                                n.minimum,
                                                "maximumAmount",
                                                n.maximum))
                        .toList());
        result.put("findings", List.copyOf(findings));
        result.put("notices", List.copyOf(notices));
        return result;
    }

    private static String hash(String key) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException();
        }
    }

    private static BigInteger number(ResultSet row, String field) throws SQLException {
        var value = row.getBigDecimal(field);
        if (value == null) throw new PaymentInspectCommand.Failure("ORDER_DATA_INVALID");
        return value.toBigIntegerExact();
    }

    private static List<BigInteger> values(ResultSet row, String... fields) throws SQLException {
        var result = new ArrayList<BigInteger>();
        for (String field : fields) result.add(number(row, field));
        return List.copyOf(result);
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        var value = row.getTimestamp(field, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        if (value == null) throw new PaymentInspectCommand.Failure("ORDER_DATA_INVALID");
        return value.toInstant();
    }

    private static Map<String, Object> map(Object... entries) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
        return map;
    }

    private record Order(
            long id,
            long user,
            String product,
            BigInteger price,
            String currency,
            BigInteger credits,
            String state,
            Long grantId,
            Instant deadline) {}

    private record Grant(
            long id,
            long user,
            boolean sourceMatches,
            BigInteger amount,
            BigInteger available,
            BigInteger reserved,
            BigInteger consumed) {}

    private record Notice(
            int stateCode, BigInteger count, BigInteger minimum, BigInteger maximum) {}

    @FunctionalInterface
    private interface Row<T> {
        T read(ResultSet row) throws SQLException;
    }

    private record Session(Connection connection, long deadline) {
        <T> List<T> query(String sql, Row<T> mapper, Object... values) throws SQLException {
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new SQLTimeoutException();
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(
                        (int) Math.min(5, Math.max(1, TimeUnit.NANOSECONDS.toSeconds(left))));
                statement.setMaxRows(32);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] instanceof Timestamp timestamp) {
                        statement.setTimestamp(
                                i + 1,
                                timestamp,
                                Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                    } else statement.setObject(i + 1, values[i]);
                }
                var result = new ArrayList<T>();
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) result.add(mapper.read(rows));
                }
                return List.copyOf(result);
            }
        }
    }
}
