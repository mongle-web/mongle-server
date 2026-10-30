package com.mongle.backend.operations.payment;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * A single-connection operator transaction. No Spring context, provider client or business worker.
 */
final class PaymentReclaimer {
    private final DataSource source;
    private final Clock clock;

    PaymentReclaimer(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    Map<String, Object> execute(PaymentReclaimRequest request) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Connection connection;
        try {
            connection = source.getConnection();
        } catch (SQLException failure) {
            throw fail("DB_CONNECTION_FAILED");
        }
        try (connection) {
            connection.setReadOnly(!request.apply());
            connection.setTransactionIsolation(
                    request.apply()
                            ? Connection.TRANSACTION_READ_COMMITTED
                            : Connection.TRANSACTION_REPEATABLE_READ);
            if (request.apply()
                    && connection.getMetaData().getDatabaseProductName().equals("MySQL")) {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    statement.execute("SET SESSION innodb_lock_wait_timeout=5");
                }
            }
            connection.setAutoCommit(false);
            try {
                var db = new PaymentInspector.Session(connection, deadline);
                var owners =
                        db.query(
                                "select user_id from payment_orders where public_id=?",
                                row -> row.getLong(1),
                                request.orderId());
                if (owners.size() != 1) throw fail("ORDER_NOT_FOUND");
                long user = owners.getFirst();
                if (request.apply()) {
                    var locked =
                            db.query(
                                    "select id from users where id=? for update",
                                    row -> row.getLong(1),
                                    user);
                    if (locked.size() != 1) throw fail("ORDER_DATA_INVALID");
                }
                var metadata =
                        db.query(
                                        """
                                        select o.id,o.user_id,o.credit_grant_id,o.price,o.credits,o.state,o.provider_transaction,
                                        o.version as order_version,a.version as account_version,g.version as grant_version
                                        from payment_orders o left join credit_accounts a on a.user_id=o.user_id
                                        left join credit_grants g on g.id=o.credit_grant_id where o.public_id=?
                                        """,
                                        row ->
                                                new Metadata(
                                                        row.getLong("id"),
                                                        row.getLong("user_id"),
                                                        row.getLong("credit_grant_id"),
                                                        row.getLong("price"),
                                                        row.getLong("credits"),
                                                        row.getString("state"),
                                                        row.getString("provider_transaction"),
                                                        row.getLong("order_version"),
                                                        row.getLong("account_version"),
                                                        row.getLong("grant_version")),
                                        request.orderId())
                                .getFirst();
                if (metadata.user != user) throw fail("ORDER_DATA_INVALID");
                String keyHash = PaymentInspector.hash(request.requestKey());
                var prior =
                        db.query(
                                """
                                select id,order_id,user_id,grant_id,request_key,operator_reference,snapshot_hash,amount
                                from payment_credit_reclamations where request_hash=? or order_id=? limit 2
                                """,
                                row ->
                                        new Prior(
                                                row.getLong("id"),
                                                row.getLong("order_id"),
                                                row.getLong("user_id"),
                                                row.getLong("grant_id"),
                                                row.getString("request_key"),
                                                row.getString("operator_reference"),
                                                row.getString("snapshot_hash"),
                                                row.getLong("amount")),
                                keyHash,
                                metadata.id);
                var report =
                        new PaymentInspector(source, clock)
                                .inspect(connection, request.orderId(), deadline);
                if (!prior.isEmpty()) {
                    var old = prior.getFirst();
                    if (prior.size() != 1
                            || old.order != metadata.id
                            || old.user != user
                            || old.grant != metadata.grant
                            || !old.key.equals(request.requestKey())
                            || !old.reference.equals(request.reference())
                            || request.apply() && !old.snapshot.equals(request.expectedSnapshot()))
                        throw fail("REQUEST_CONFLICT");
                    if (old.amount != metadata.credits
                            || !metadata.state.equals("CREDIT_RECLAIMED")
                            || !((List<?>) report.get("findings")).isEmpty())
                        throw fail("RECLAMATION_RECORD_INCONSISTENT");
                    return response(
                            request,
                            "ALREADY_RECLAIMED",
                            old.snapshot,
                            old.amount,
                            report,
                            List.of());
                }
                var notifications =
                        db.query(
                                        """
                                        select count(*) as total,
                                        coalesce(sum(case when state_code in (9,64) then 1 else 0 end),0) as cancellations,
                                        coalesce(sum(case when state_code=4 then 1 else 0 end),0) as completions,
                                        coalesce(sum(case when state_code in (70,71) then 1 else 0 end),0) as partials,
                                        coalesce(sum(case when state_code not in (1,4,8,9,10,32,64,70,71) or amount<>? or provider_transaction<>? then 1 else 0 end),0) as invalid,
                                        max(case when state_code=4 then responded_at else null end) as completed_at,
                                        max(case when state_code in (9,64) then responded_at else null end) as cancelled_at
                                        from payment_notifications where order_id=?
                                        """,
                                        row ->
                                                new Notifications(
                                                        row.getLong("total"),
                                                        row.getLong("cancellations"),
                                                        row.getLong("completions"),
                                                        row.getLong("partials"),
                                                        row.getLong("invalid"),
                                                        instant(row, "completed_at"),
                                                        instant(row, "cancelled_at")),
                                        metadata.price,
                                        metadata.transaction,
                                        metadata.id)
                                .getFirst();
                var bindings =
                        db.query(
                                "select count(*) from payment_transaction_bindings where"
                                        + " transaction_id=? and order_id=?",
                                row -> row.getLong(1),
                                metadata.transaction,
                                metadata.id);
                var hold =
                        db.query(
                                "select created_at from credit_payment_holds where grant_id=? and"
                                        + " user_id=?",
                                row -> instant(row, "created_at"),
                                metadata.grant,
                                user);
                var reasons = new ArrayList<String>();
                if (!((List<?>) report.get("findings")).isEmpty())
                    reasons.add("INTERNAL_RECORDS_INCONSISTENT");
                if (!metadata.state.equals("PAYMENT_REVIEW")) reasons.add("ORDER_NOT_IN_REVIEW");
                if (metadata.transaction == null
                        || bindings.getFirst() != 1
                        || notifications.invalid > 0) reasons.add("TRANSACTION_EVIDENCE_MISMATCH");
                if (notifications.cancellations < 1 || notifications.completions < 1)
                    reasons.add("FULL_APPROVAL_CANCELLATION_REQUIRED");
                if (notifications.partials > 0) reasons.add("PARTIAL_CANCELLATION_NOT_SUPPORTED");
                if (notifications.completed != null
                        && notifications.cancelled != null
                        && notifications.completed.isAfter(notifications.cancelled))
                    reasons.add("CONFLICTING_NOTIFICATION_ORDER");
                if (hold.size() != 1) reasons.add("PURCHASE_HOLD_REQUIRED");
                var purchase = (Map<?, ?>) report.get("purchase");
                var account = (Map<?, ?>) report.get("account");
                if (purchase == null
                        || number(purchase, "amount") != metadata.credits
                        || number(purchase, "available") != metadata.credits
                        || number(purchase, "reserved") != 0
                        || number(purchase, "consumed") != 0
                        || number(purchase, "reclaimed") != 0
                        || number(account, "available") < metadata.credits)
                    reasons.add("PURCHASE_NOT_WHOLE_AND_UNUSED");
                // Hash only a canonical structured snapshot, never expose provider IDs or private
                // source keys.
                var snapshot = new LinkedHashMap<String, Object>();
                snapshot.put("orderId", request.orderId());
                snapshot.put("requestKey", request.requestKey());
                snapshot.put("reference", request.reference());
                snapshot.put("orderVersion", metadata.orderVersion);
                snapshot.put("accountVersion", metadata.accountVersion);
                snapshot.put("grantVersion", metadata.grantVersion);
                var stable = new LinkedHashMap<>(report);
                stable.remove("observedAt");
                snapshot.put("report", stable);
                snapshot.put(
                        "notifications",
                        List.of(
                                notifications.total,
                                notifications.cancellations,
                                notifications.completions,
                                notifications.partials,
                                notifications.invalid));
                snapshot.put("completedAt", notifications.completed);
                snapshot.put("cancelledAt", notifications.cancelled);
                snapshot.put("holdCreatedAt", hold);
                String token =
                        PaymentInspector.hash(
                                JsonMapper.builder().build().writeValueAsString(snapshot));
                if (!request.apply())
                    return response(
                            request,
                            reasons.isEmpty() ? "WOULD_RECLAIM" : "REFUSED",
                            token,
                            metadata.credits,
                            report,
                            reasons);
                if (!reasons.isEmpty()) throw fail("RECLAMATION_NOT_ALLOWED");
                if (!token.equals(request.expectedSnapshot())) throw fail("SNAPSHOT_CHANGED");
                long after = Math.subtractExact(number(account, "available"), metadata.credits);
                update(
                        db,
                        "update credit_grants set available=0,version=version+1 where id=? and"
                                + " version=? and available=? and reserved=0 and consumed=0",
                        metadata.grant,
                        metadata.grantVersion,
                        metadata.credits);
                update(
                        db,
                        "update credit_accounts set"
                                + " available=available-?,version=version+1,updated_at=? where"
                                + " user_id=? and version=? and available>=?",
                        metadata.credits,
                        now(),
                        user,
                        metadata.accountVersion,
                        metadata.credits);
                update(
                        db,
                        """
                        insert into credit_ledger(user_id,entry_key,kind,source_type,grant_id,available_delta,reserved_delta,available_after,reserved_after,created_at)
                        values(?,?,'RECLAIM','PAYMENT',?, ?,0,?,?,?)
                        """,
                        user,
                        "RECLAIM:" + metadata.grant,
                        metadata.grant,
                        -metadata.credits,
                        after,
                        number(account, "reserved"),
                        now());
                update(
                        db,
                        """
                        insert into payment_credit_reclamations(order_id,user_id,grant_id,request_key,request_hash,operator_reference,snapshot_hash,amount,created_at)
                        values(?,?,?,?,?,?,?,?,?)
                        """,
                        metadata.id,
                        user,
                        metadata.grant,
                        request.requestKey(),
                        keyHash,
                        request.reference(),
                        token,
                        metadata.credits,
                        now());
                update(
                        db,
                        "delete from credit_payment_holds where grant_id=? and user_id=?",
                        metadata.grant,
                        user);
                update(
                        db,
                        "update payment_orders set"
                            + " state='CREDIT_RECLAIMED',payment_url=null,version=version+1,updated_at=?"
                            + " where id=? and version=? and state='PAYMENT_REVIEW'",
                        now(),
                        metadata.id,
                        metadata.orderVersion);
                var afterReport =
                        new PaymentInspector(source, clock)
                                .inspect(connection, request.orderId(), deadline);
                if (!((List<?>) afterReport.get("findings")).isEmpty())
                    throw fail("POSTCONDITION_FAILED");
                connection.commit();
                return response(
                        request, "RECLAIMED", token, metadata.credits, afterReport, List.of());
            } finally {
                connection.rollback();
            }
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        var value = row.getTimestamp(field, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        return value == null ? null : value.toInstant();
    }

    private static long number(Map<?, ?> map, String field) {
        return ((BigInteger) map.get(field)).longValueExact();
    }

    private static void update(PaymentInspector.Session db, String sql, Object... values)
            throws SQLException {
        long left = db.deadline() - System.nanoTime();
        if (left <= 0) throw new SQLTimeoutException();
        try (var statement = db.connection().prepareStatement(sql)) {
            statement.setQueryTimeout(
                    (int) Math.min(5, Math.max(1, TimeUnit.NANOSECONDS.toSeconds(left))));
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            if (statement.executeUpdate() != 1) throw fail("RECLAMATION_WRITE_CONFLICT");
        }
    }

    private static Map<String, Object> response(
            PaymentReclaimRequest request,
            String status,
            String snapshot,
            long amount,
            Map<String, Object> report,
            List<String> reasons) {
        var result = new LinkedHashMap<String, Object>();
        result.put("event", "payment-reclamation-completed");
        result.put("scope", "INTERNAL_DATABASE_ONLY");
        result.put("operation", request.operation());
        result.put("status", status);
        result.put("orderId", request.orderId());
        result.put("snapshot", snapshot);
        result.put("amount", amount);
        result.put("reasons", reasons);
        var account = (Map<?, ?>) report.get("account");
        result.put(
                "expectedAvailableAfter",
                status.equals("WOULD_RECLAIM")
                        ? Math.subtractExact(number(account, "available"), amount)
                        : status.equals("REFUSED") ? null : account.get("available"));
        result.put(
                "remainingHoldCount",
                status.equals("WOULD_RECLAIM")
                        ? number(account, "holdCount") - 1
                        : account.get("holdCount"));
        result.put("inspection", report);
        return result;
    }

    private static PaymentInspectCommand.Failure fail(String code) {
        return new PaymentInspectCommand.Failure(code);
    }

    private record Metadata(
            long id,
            long user,
            long grant,
            long price,
            long credits,
            String state,
            String transaction,
            long orderVersion,
            long accountVersion,
            long grantVersion) {}

    private record Notifications(
            long total,
            long cancellations,
            long completions,
            long partials,
            long invalid,
            Instant completed,
            Instant cancelled) {}

    private record Prior(
            long id,
            long order,
            long user,
            long grant,
            String key,
            String reference,
            String snapshot,
            long amount) {}
}
