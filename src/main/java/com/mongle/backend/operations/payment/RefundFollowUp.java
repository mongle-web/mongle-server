package com.mongle.backend.operations.payment;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.mongle.backend.operations.payment.RefundResolver.*;

final class RefundFollowUp {
    private static final int PAGE_SIZE = 20;
    private static final int EVIDENCE_LIMIT = 32;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RefundFollowUp() {}

    static Map<String, Object> list(PaymentInspector.Session db, String afterReceiptId) throws SQLException {
        var rows = db.query("""
                select r.receipt_id,o.public_id from payment_refund_requests r
                join payment_refund_resolutions s on s.refund_id=r.id and s.status='COMPLETED'
                join payment_orders o on o.id=r.order_id and o.user_id=r.user_id
                  and o.credit_grant_id=r.grant_id and o.state='PAYMENT_REVIEW'
                join credit_payment_holds h on h.grant_id=r.grant_id and h.user_id=r.user_id
                where r.receipt_id>?
                order by r.receipt_id limit ?
                """, row -> new Candidate(row.getString("receipt_id"), row.getString("public_id")),
                afterReceiptId, PAGE_SIZE + 1);
        var result = new LinkedHashMap<String, Object>();
        result.put("event", "refund-follow-ups");
        result.put("items", rows.stream().limit(PAGE_SIZE).toList());
        result.put("nextAfterReceiptId", rows.size() > PAGE_SIZE ? rows.get(PAGE_SIZE - 1).receiptId() : null);
        return result;
    }

    static Map<String, Object> execute(PaymentInspector.Session db, Clock clock, RefundResolutionRequest request,
            Receipt receipt, Resolution original, Map<String, Object> report, PaymentInspector inspector)
            throws SQLException {
        if (original == null || !"COMPLETED".equals(original.status())) throw fail("REFUND_NOT_COMPLETED");
        if (request.reference() != null) {
            var previous = db.query("""
                    select id,additional_amount,confirmed_total,operator_reference,snapshot_hash,confirmed_at
                    from payment_refund_follow_ups where refund_id=? and operator_reference=?
                    """, RefundFollowUp::round, receipt.id(), request.reference());
            if (!previous.isEmpty()) {
                var found = previous.getFirst();
                if (!request.apply() || found.amount() != request.amount()
                        || !found.reference().equals(request.reference())
                        || !found.snapshot().equals(request.expectedSnapshot())) {
                    throw fail("FOLLOW_UP_REFERENCE_USED");
                }
                return applied(request, receipt, found);
            }
        }

        var verified = evidence(db, receipt);
        var recorded = db.query("""
                select provider_transaction,amount,full_cancellation,follow_up_id
                from payment_refund_evidence where refund_id=? order by provider_transaction
                """, row -> new Recorded(row.getString("provider_transaction"), row.getLong("amount"),
                        row.getBoolean("full_cancellation"), row.getLong("follow_up_id")), receipt.id());
        var rounds = db.query("""
                select id,additional_amount,confirmed_total,operator_reference,snapshot_hash,confirmed_at
                from payment_refund_follow_ups where refund_id=? order by id
                """, RefundFollowUp::round, receipt.id());
        var byTransaction = new HashMap<String, Recorded>();
        recorded.forEach(item -> byTransaction.put(item.transaction(), item));
        var pending = verified.items().stream()
                .filter(item -> !byTransaction.containsKey(item.transaction())).toList();
        BigInteger approvedTotal = sum(recorded);
        BigInteger pendingTotal = pending.stream().map(item -> BigInteger.valueOf(item.minimum()))
                .reduce(BigInteger.ZERO, BigInteger::add);
        var findings = new ArrayList<String>();

        if (!((List<?>) report.get("findings")).isEmpty() || receipt.linkedGrant() != receipt.grant()
                || receipt.available() != 0 || receipt.reserved() != 0 || receipt.forfeited() != 0
                || !validHistory(recorded, rounds, original.refunded())) {
            findings.add("REFUND_RECORDS_INCONSISTENT");
        }
        if (verified.items().size() >= EVIDENCE_LIMIT || !verified.approvalMatches()
                || verified.items().stream().anyMatch(item -> !item.matches(receipt))
                || verified.items().size() - pending.size() != recorded.size()
                || verified.items().stream().anyMatch(item -> {
                    var existing = byTransaction.get(item.transaction());
                    return existing != null && (existing.amount() != item.minimum()
                            || existing.full() != (item.minFull() == 1));
                })) {
            findings.add("CANCELLATION_EVIDENCE_CONFLICT");
        }
        if (verified.total().compareTo(BigInteger.valueOf(receipt.price())) > 0) {
            findings.add("CANCELLATION_EXCEEDS_ORDER_AMOUNT");
        }
        if (pending.isEmpty()) findings.add("NO_NEW_CONFIRMED_CANCELLATION");
        if (!receipt.hold() || !"PAYMENT_REVIEW".equals(receipt.orderState())) {
            findings.add("FOLLOW_UP_HOLD_REQUIRED");
        }

        var response = summary(request, receipt);
        response.put("orderState", receipt.orderState());
        response.put("initialRefundedAmount", original.refunded());
        response.put("confirmedCancellationAmount", approvedTotal);
        response.put("unreviewedCancellationAmount", pendingTotal);
        response.put("unreviewedCancellationCount", pending.size());
        response.put("previousFollowUps", rounds.stream()
                .map(round -> new RoundSummary(round.amount(), round.total(), round.confirmedAt())).toList());
        if (request.mode() == RefundResolutionRequest.Mode.INSPECT_FOLLOW_UP) {
            response.put("findings", findings);
            return response;
        }

        if (!pendingTotal.equals(BigInteger.valueOf(request.amount()))) {
            findings.add("CANCELLATION_DOES_NOT_MATCH_APPROVAL");
        }
        var state = new LinkedHashMap<>(report);
        state.remove("observedAt");
        String snapshot = PaymentInspector.hash(JSON.writeValueAsString(List.of(request.receiptId(),
                request.amount(), request.reference(), receipt.orderVersion(), receipt.accountVersion(),
                receipt.grantVersion(), state, verified.items(), recorded, rounds.size())));
        response.put("decision", findings.isEmpty() ? "READY" : "REVIEW_REQUIRED");
        response.put("snapshot", snapshot);
        response.put("findings", findings);
        if (!request.apply()) return response;
        if (!findings.isEmpty()) throw fail(findings.getFirst());
        if (!snapshot.equals(request.expectedSnapshot())) throw fail("SNAPSHOT_CHANGED");

        var now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        long total = approvedTotal.add(pendingTotal).longValueExact();
        update(db, """
                insert into payment_refund_follow_ups(refund_id,additional_amount,confirmed_total,
                    operator_reference,snapshot_hash,confirmed_at) values(?,?,?,?,?,?)
                """, receipt.id(), request.amount(), total, request.reference(), snapshot, now);
        var round = db.query("""
                select id,additional_amount,confirmed_total,operator_reference,snapshot_hash,confirmed_at
                from payment_refund_follow_ups where refund_id=? and operator_reference=?
                """, RefundFollowUp::round, receipt.id(), request.reference()).getFirst();
        for (var item : pending) {
            update(db, """
                    insert into payment_refund_evidence(refund_id,provider_transaction,amount,full_cancellation,follow_up_id)
                    values(?,?,?,?,?)
                    """, receipt.id(), item.transaction(), item.minimum(), item.minFull() == 1, round.id());
        }
        update(db, """
                update credit_accounts set version=version+1,updated_at=? where user_id=? and version=?
                """, now, receipt.user(), receipt.accountVersion());
        update(db, "delete from credit_payment_holds where grant_id=? and user_id=?", receipt.grant(), receipt.user());
        update(db, """
                update payment_orders set state='REFUND_SETTLED',payment_url=null,version=version+1,updated_at=?
                where id=? and user_id=? and version=? and state='PAYMENT_REVIEW'
                """, now, receipt.orderKey(), receipt.user(), receipt.orderVersion());
        var after = inspector.inspect(db.connection(), receipt.orderId(), db.deadline());
        if (!((List<?>) after.get("findings")).isEmpty()) throw fail("POSTCONDITION_FAILED");
        return applied(request, receipt, round);
    }

    private static boolean validHistory(List<Recorded> recorded, List<Round> rounds, long original) {
        if (recorded.size() >= EVIDENCE_LIMIT || rounds.size() >= EVIDENCE_LIMIT) return false;
        var totals = new HashMap<Long, BigInteger>();
        for (var item : recorded) {
            totals.merge(item.roundId(), BigInteger.valueOf(item.amount()), BigInteger::add);
        }
        BigInteger cumulative = totals.remove(0L);
        if (!BigInteger.valueOf(original).equals(cumulative)) return false;
        for (var round : rounds) {
            var amount = totals.remove(round.id());
            if (!BigInteger.valueOf(round.amount()).equals(amount)) return false;
            cumulative = cumulative.add(amount);
            if (!BigInteger.valueOf(round.total()).equals(cumulative)) return false;
        }
        return totals.isEmpty();
    }

    private static Round round(ResultSet row) throws SQLException {
        return new Round(row.getLong("id"), row.getLong("additional_amount"), row.getLong("confirmed_total"),
                row.getString("operator_reference"), row.getString("snapshot_hash"), instant(row, "confirmed_at"));
    }

    private static BigInteger sum(List<Recorded> items) {
        return items.stream().map(item -> BigInteger.valueOf(item.amount())).reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static Map<String, Object> summary(RefundResolutionRequest request, Receipt receipt) {
        var result = new LinkedHashMap<String, Object>();
        result.put("event", "refund-follow-up");
        result.put("receiptId", request.receiptId());
        result.put("orderId", receipt.orderId());
        return result;
    }

    private static Map<String, Object> applied(RefundResolutionRequest request, Receipt receipt, Round round) {
        var result = summary(request, receipt);
        result.put("status", "APPLIED");
        result.put("additionalAmount", round.amount());
        result.put("confirmedCancellationAmount", round.total());
        result.put("confirmedAt", round.confirmedAt());
        return result;
    }

    private record Candidate(String receiptId, String orderId) {}
    private record Recorded(String transaction, long amount, boolean full, long roundId) {}
    private record Round(long id, long amount, long total, String reference, String snapshot, Instant confirmedAt) {}
    private record RoundSummary(long additionalAmount, long confirmedCancellationAmount, Instant confirmedAt) {}
}
