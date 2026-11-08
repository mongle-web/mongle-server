package com.mongle.backend.operations.payment;

import com.mongle.backend.domain.payment.RefundEstimate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Reconciles externally verified PayApp cancellations using the same user lock as the application. */
final class RefundResolver {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DataSource source;
    private final Clock clock;

    RefundResolver(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    Map<String, Object> execute(RefundResolutionRequest request) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection connection = connect()) {
            connection.setReadOnly(!request.apply());
            connection.setTransactionIsolation(request.apply()
                    ? Connection.TRANSACTION_READ_COMMITTED : Connection.TRANSACTION_REPEATABLE_READ);
            if (request.apply() && "MySQL".equals(connection.getMetaData().getDatabaseProductName())) {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    statement.execute("SET SESSION innodb_lock_wait_timeout=5");
                }
            }
            connection.setAutoCommit(false);
            try {
                var db = new PaymentInspector.Session(connection, deadline);
                long user = one(db.query("select user_id from payment_refund_requests where receipt_id=?",
                        row -> row.getLong(1), request.receiptId()), "REFUND_NOT_FOUND");
                if (request.apply()) {
                    one(db.query("select id from users where id=? for update",
                            row -> row.getLong(1), user), "REFUND_NOT_FOUND");
                }
                var receipt = load(db, request.receiptId(), user);
                var prior = resolution(db, receipt.id);
                var report = new PaymentInspector(source, clock).inspect(connection, receipt.orderId, deadline);
                var response = summary(request, receipt, report, prior);
                if (prior != null) {
                    if (request.apply()) verifyReplay(request, prior);
                    return response;
                }

                var evidence = evidence(db, receipt);
                var findings = findings(receipt, report, evidence);
                response.put("confirmedCancellationAmount", evidence.total);
                response.put("cancellationCount", evidence.items.size());
                response.put("findings", findings);
                if (request.mode() == RefundResolutionRequest.Mode.INSPECT) return response;

                var reasons = new ArrayList<>(findings);
                validateAction(request, receipt, evidence, reasons);
                var snapshot = snapshot(request, receipt, report, evidence);
                response.put("snapshot", snapshot);
                response.put("action", request.action());
                response.put("approvedAmount", request.amount());
                response.put("revokedCredits", request.credits());
                response.put("decision", reasons.isEmpty() ? "READY" : "REVIEW_REQUIRED");
                response.put("findings", List.copyOf(reasons));
                if (!request.apply()) return response;
                if (!reasons.isEmpty()) throw fail(reasons.getFirst());
                if (!snapshot.equals(request.expectedSnapshot())) throw fail("SNAPSHOT_CHANGED");

                apply(db, request, receipt, evidence, snapshot);
                var after = new PaymentInspector(source, clock).inspect(connection, receipt.orderId, deadline);
                if (!((List<?>) after.get("findings")).isEmpty()) throw fail("POSTCONDITION_FAILED");
                var resolved = resolution(db, receipt.id);
                var result = summary(request, receipt, after, resolved);
                connection.commit();
                return result;
            } finally {
                connection.rollback();
            }
        }
    }

    private Connection connect() {
        try {
            return source.getConnection();
        } catch (SQLException failure) {
            throw fail("DB_CONNECTION_FAILED");
        }
    }

    private static Receipt load(PaymentInspector.Session db, String receiptId, long user) throws SQLException {
        return one(db.query("""
                select r.id,r.user_id,r.order_id,r.grant_id,r.reason,r.created_at,
                    r.available_at_request,r.reserved_at_request,o.public_id,o.provider_transaction,
                    o.credit_grant_id,o.price,o.credits,o.state,o.version as order_version,o.approved_at,
                    g.available,g.reserved,g.consumed,g.forfeited,g.version as grant_version,
                    a.available as account_available,a.reserved as account_reserved,a.version as account_version,
                    h.grant_id as hold_id
                from payment_refund_requests r
                join payment_orders o on o.id=r.order_id and o.user_id=r.user_id
                join credit_grants g on g.id=r.grant_id and g.user_id=r.user_id
                join credit_accounts a on a.user_id=r.user_id
                left join credit_payment_holds h on h.grant_id=g.id and h.user_id=r.user_id
                where r.receipt_id=? and r.user_id=?
                """, row -> new Receipt(row.getLong("id"), row.getLong("user_id"), row.getLong("order_id"),
                row.getLong("grant_id"), row.getString("reason"), instant(row, "created_at"),
                row.getLong("available_at_request"), row.getLong("reserved_at_request"),
                row.getString("public_id"), row.getString("provider_transaction"), row.getLong("credit_grant_id"),
                row.getLong("price"), row.getLong("credits"), row.getString("state"), row.getLong("order_version"),
                instant(row, "approved_at"), row.getLong("available"), row.getLong("reserved"),
                row.getLong("consumed"), row.getLong("forfeited"), row.getLong("grant_version"),
                row.getLong("account_available"), row.getLong("account_reserved"), row.getLong("account_version"),
                row.getObject("hold_id") != null), receiptId, user), "REFUND_RECORDS_INCONSISTENT");
    }

    private static Resolution resolution(PaymentInspector.Session db, long id) throws SQLException {
        var rows = db.query("""
                select status,refunded_amount,revoked_credits,basis,operator_reference,snapshot_hash,resolved_at
                from payment_refund_resolutions where refund_id=?
                """, row -> new Resolution(row.getString("status"), row.getLong("refunded_amount"),
                row.getLong("revoked_credits"), row.getString("basis"), row.getString("operator_reference"),
                row.getString("snapshot_hash"), instant(row, "resolved_at")), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Evidence evidence(PaymentInspector.Session db, Receipt receipt) throws SQLException {
        var items = db.query("""
                select n.provider_transaction,min(n.amount) as minimum,max(n.amount) as maximum,
                    min(case when n.state_code in (9,64) then 1 else 0 end) as min_full,
                    max(case when n.state_code in (9,64) then 1 else 0 end) as max_full,
                    max(case when b.order_id=n.order_id then 0 else 1 end) as bad_binding,
                    min(n.responded_at) as first_notice
                from payment_notifications n
                left join payment_transaction_bindings b on b.transaction_id=n.provider_transaction
                where n.order_id=? and n.state_code in (9,64,70,71)
                group by n.provider_transaction order by n.provider_transaction
                """, row -> new Cancellation(row.getString("provider_transaction"), row.getLong("minimum"),
                row.getLong("maximum"), row.getInt("min_full"), row.getInt("max_full"),
                row.getInt("bad_binding") != 0, instant(row, "first_notice")), receipt.orderKey);
        var total = items.stream().map(item -> BigInteger.valueOf(item.minimum))
                .reduce(BigInteger.ZERO, BigInteger::add);
        boolean earlierCancellation = one(db.query("""
                select count(*) from payment_notifications n where n.order_id=? and n.state_code in (8,32)
                """, row -> row.getLong(1), receipt.orderKey), "REFUND_RECORDS_INCONSISTENT") != 0;
        var approval = db.query("""
                select n.responded_at from payment_notifications n
                join payment_transaction_bindings b on b.transaction_id=n.provider_transaction and b.order_id=n.order_id
                where n.order_id=? and n.state_code=4 and n.amount=? and n.provider_transaction=?
                order by n.responded_at limit 1
                """, row -> instant(row, "responded_at"), receipt.orderKey, receipt.price, receipt.originalTransaction);
        boolean approvalMatches = !approval.isEmpty()
                && items.stream().noneMatch(item -> item.firstNotice.isBefore(approval.getFirst()));
        return new Evidence(items, total, earlierCancellation, approvalMatches);
    }

    private static List<String> findings(Receipt receipt, Map<String, Object> report, Evidence evidence) {
        var result = new ArrayList<String>();
        if (!((List<?>) report.get("findings")).isEmpty() || receipt.linkedGrant != receipt.grant
                || receipt.forfeited != 0 || receipt.available > receipt.accountAvailable
                || !Set.of("PAID", "PAYMENT_REVIEW").contains(receipt.orderState)
                || BigInteger.valueOf(receipt.available).add(BigInteger.valueOf(receipt.reserved))
                        .compareTo(BigInteger.valueOf(receipt.initialAvailable)
                                .add(BigInteger.valueOf(receipt.initialReserved))) > 0) {
            result.add("REFUND_RECORDS_INCONSISTENT");
        }
        // Session caps rows at 32. Treat reaching the cap as incomplete evidence.
        if (evidence.items.size() >= 32 || evidence.items.stream().anyMatch(item -> !item.matches(receipt))) {
            result.add("CANCELLATION_EVIDENCE_CONFLICT");
        }
        if (evidence.total.compareTo(BigInteger.valueOf(receipt.price)) > 0) {
            result.add("CANCELLATION_EXCEEDS_ORDER_AMOUNT");
        }
        return result;
    }

    private static void validateAction(RefundResolutionRequest request, Receipt receipt,
            Evidence evidence, List<String> reasons) {
        if (request.action() != RefundResolutionRequest.Action.SETTLE) {
            if (!evidence.items.isEmpty() || evidence.requestCancellation || receipt.hold
                    || !"PAID".equals(receipt.orderState)) {
                reasons.add("PROVIDER_STATUS_REQUIRES_REVIEW");
            }
            return;
        }

        if (receipt.reserved != 0) reasons.add("RESERVATION_STILL_OPEN");
        if (request.credits() != receipt.available || request.amount() > receipt.price) {
            reasons.add("APPROVAL_OUTSIDE_PURCHASE_BALANCE");
        }
        if ("STANDARD".equals(request.basis())) {
            Long estimate = standardEstimate(receipt);
            if (estimate == null || estimate != request.amount()) reasons.add("APPROVAL_REQUIRES_REVIEW");
        }
        if (evidence.items.isEmpty()) {
            reasons.add("AWAITING_CANCEL_CONFIRMATION");
        } else if (!evidence.approvalMatches || !evidence.total.equals(BigInteger.valueOf(request.amount())) || !receipt.hold
                || !"PAYMENT_REVIEW".equals(receipt.orderState)) {
            reasons.add("CANCELLATION_DOES_NOT_MATCH_APPROVAL");
        }
    }

    private static Long standardEstimate(Receipt receipt) {
        if (!"STANDARD".equals(receipt.reason) || receipt.reserved != 0 || receipt.approved == null
                || receipt.approved.isAfter(receipt.received) || receipt.purchased <= 0 || receipt.price <= 0
                || receipt.available < 0 || receipt.available > receipt.purchased) return null;
        return RefundEstimate.standardAmount(receipt.price, receipt.purchased, receipt.available,
                RefundEstimate.standardFeePercent(receipt.approved, receipt.received));
    }

    private static void verifyReplay(RefundResolutionRequest request, Resolution prior) {
        if (!prior.status.equals(request.status()) || prior.refunded != request.amount()
                || prior.revoked != request.credits() || !prior.basis.equals(request.basis())
                || !prior.reference.equals(request.reference()) || !prior.snapshot.equals(request.expectedSnapshot())) {
            throw fail("REFUND_ALREADY_RESOLVED");
        }
    }

    private void apply(PaymentInspector.Session db, RefundResolutionRequest request, Receipt receipt,
            Evidence evidence, String snapshot) throws SQLException {
        var now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        boolean settled = request.action() == RefundResolutionRequest.Action.SETTLE;
        boolean clearHold = settled && receipt.hold;
        update(db, """
                insert into payment_refund_resolutions(refund_id,status,refunded_amount,revoked_credits,
                    released_payment_hold,basis,operator_reference,snapshot_hash,resolved_at)
                values(?,?,?,?,?,?,?,?,?)
                """, receipt.id, request.status(), request.amount(), request.credits(), clearHold,
                request.basis(), request.reference(), snapshot, now);
        // Account version invalidates older image transactions when releasing a hold, even with zero debit.
        update(db, """
                update credit_accounts set available=available-?,version=version+1,updated_at=?
                where user_id=? and version=? and available>=?
                """, request.credits(), now, receipt.user, receipt.accountVersion, request.credits());

        if (!settled) return;
        if (request.credits() > 0) {
            update(db, """
                    update credit_grants set available=available-?,version=version+1
                    where id=? and user_id=? and version=? and available=? and reserved=0
                    """, request.credits(), receipt.grant, receipt.user, receipt.grantVersion, request.credits());
            update(db, """
                    insert into credit_ledger(user_id,entry_key,kind,source_type,grant_id,
                        available_delta,reserved_delta,available_after,reserved_after,created_at)
                    values(?,?,'RECLAIM','PAYMENT',?,?,0,?,?,?)
                    """, receipt.user, "RECLAIM:" + receipt.grant, receipt.grant, -request.credits(),
                    receipt.accountAvailable - request.credits(), receipt.accountReserved, now);
        }
        for (var item : evidence.items) {
            update(db, """
                    insert into payment_refund_evidence(refund_id,provider_transaction,amount,full_cancellation)
                    values(?,?,?,?)
                    """, receipt.id, item.transaction, item.minimum, item.minFull == 1);
        }
        update(db, "delete from credit_payment_holds where grant_id=? and user_id=?", receipt.grant, receipt.user);
        update(db, """
                update payment_orders set state='REFUND_SETTLED',payment_url=null,version=version+1,updated_at=?
                where id=? and user_id=? and version=? and state='PAYMENT_REVIEW'
                """, now, receipt.orderKey, receipt.user, receipt.orderVersion);
    }

    private static Map<String, Object> summary(RefundResolutionRequest request, Receipt receipt,
            Map<String, Object> report, Resolution prior) {
        var response = new LinkedHashMap<String, Object>();
        response.put("event", "refund-resolution");
        response.put("receiptId", request.receiptId());
        response.put("orderId", receipt.orderId);
        response.put("orderState", report.get("order") instanceof Map<?, ?> order ? order.get("storedState") : null);
        response.put("status", prior == null ? "RECEIVED" : prior.status);
        response.put("reason", receipt.reason);
        response.put("purchaseAmount", receipt.price);
        response.put("unusedCredits", report.get("purchase") instanceof Map<?, ?> purchase ? purchase.get("available") : null);
        response.put("reservedCredits", receipt.reserved);
        response.put("usedCredits", receipt.consumed);
        response.put("standardEstimatedAmount", prior == null ? standardEstimate(receipt) : null);
        if (prior != null) {
            response.put("refundedAmount", prior.refunded);
            response.put("revokedCredits", prior.revoked);
            response.put("resolvedAt", prior.resolvedAt);
        }
        return response;
    }

    private static String snapshot(RefundResolutionRequest request, Receipt r,
            Map<String, Object> report, Evidence evidence) {
        var state = new LinkedHashMap<>(report);
        state.remove("observedAt");
        var approved = List.of(request.receiptId(), request.action(), request.amount(), request.credits(),
                request.basis(), request.reference());
        // Include stable cancellation IDs privately. Only the hash is emitted.
        return PaymentInspector.hash(JSON.writeValueAsString(List.of(approved, r.orderVersion, r.accountVersion,
                r.grantVersion, state, evidence.requestCancellation, evidence.approvalMatches, evidence.items)));
    }

    private static void update(PaymentInspector.Session db, String sql, Object... values) throws SQLException {
        long left = db.deadline() - System.nanoTime();
        if (left <= 0) throw new SQLTimeoutException();
        try (var statement = db.connection().prepareStatement(sql)) {
            statement.setQueryTimeout((int) Math.min(5, Math.max(1, TimeUnit.NANOSECONDS.toSeconds(left))));
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            if (statement.executeUpdate() != 1) throw fail("REFUND_WRITE_CONFLICT");
        }
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        var value = row.getTimestamp(field, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        return value == null ? null : value.toInstant();
    }

    private static <T> T one(List<T> rows, String code) {
        if (rows.size() != 1) throw fail(code);
        return rows.getFirst();
    }

    private static PaymentInspectCommand.Failure fail(String code) {
        return new PaymentInspectCommand.Failure(code);
    }

    private record Receipt(long id, long user, long orderKey, long grant, String reason, Instant received,
            long initialAvailable, long initialReserved, String orderId, String originalTransaction, long linkedGrant,
            long price, long purchased, String orderState, long orderVersion, Instant approved, long available,
            long reserved, long consumed, long forfeited, long grantVersion, long accountAvailable,
            long accountReserved, long accountVersion, boolean hold) {}

    private record Resolution(String status, long refunded, long revoked, String basis, String reference,
            String snapshot, Instant resolvedAt) {}

    private record Evidence(List<Cancellation> items, BigInteger total, boolean requestCancellation,
            boolean approvalMatches) {}

    private record Cancellation(String transaction, long minimum, long maximum, int minFull,
            int maxFull, boolean badBinding, Instant firstNotice) {
        boolean matches(Receipt receipt) {
            if (receipt.originalTransaction == null || transaction == null || !transaction.matches("[1-9][0-9]{0,39}")
                    || badBinding || minimum <= 0 || minimum != maximum || minFull != maxFull) return false;
            return minFull == 1 ? transaction.equals(receipt.originalTransaction) && minimum == receipt.price
                    : !transaction.equals(receipt.originalTransaction) && minimum < receipt.price;
        }
    }
}
