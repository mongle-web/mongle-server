package com.mongle.backend.domain.payment;

import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {
    private static final Set<Integer> TERMINAL_FEEDBACK = Set.of(4, 8, 9, 32, 64);

    private final PaymentOrderRepository orders;
    private final PaymentNotificationRepository notifications;
    private final PaymentNotificationRecoveryRepository recoveries;
    private final PaymentTransactionRepository bindings;
    private final PaymentTransactions transactions;
    private final PaymentFeedbackService feedback;
    private final PayAppFeedbackVerifier verifier;
    private final Clock authClock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result preview(String orderId, String reference, PaymentRecoveryEvidence evidence) {
        var event = authenticated(orderId, evidence);
        var order = orders.findByPublicId(orderId).orElseThrow(() -> failure("ORDER_NOT_FOUND"));
        return evaluate(order, event, reference, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result apply(String orderId, String reference, String expectedSnapshot, PaymentRecoveryEvidence evidence) {
        var event = authenticated(orderId, evidence);
        var user = orders.findOwnerByPublicId(orderId).orElseThrow(() -> failure("ORDER_NOT_FOUND"));
        transactions.requireAccountingUser(user);
        var order = orders.findByPublicIdAndUserId(orderId, user).orElseThrow(() -> failure("ORDER_NOT_FOUND"));
        var preview = evaluate(order, event, reference, expectedSnapshot);
        if (!"READY".equals(preview.status())) return preview;
        if (!PaymentHashes.equal(preview.snapshot(), expectedSnapshot)) throw failure("SNAPSHOT_CHANGED");

        feedback.bindVerifiedOrder(order, event);
        feedback.applyVerified(order, event);
        recoveries.saveAndFlush(PaymentNotificationRecovery.record(order.getId(), event.eventHash(), reference,
                evidenceHash(event), expectedSnapshot, authClock.instant().truncatedTo(ChronoUnit.MICROS)));
        return result("RECOVERED", order, null);
    }

    private PayAppFeedback authenticated(String orderId, PaymentRecoveryEvidence evidence) {
        PayAppFeedback event;
        try {
            event = verifier.verify(PayAppForm.decode(evidence.originalForm()));
        } catch (BusinessException | IllegalArgumentException problem) {
            throw failure("INVALID_ORIGINAL_NOTIFICATION");
        }
        if (!orderId.equals(event.orderId()) || !event.transactionId().equals(evidence.confirmedTransaction())
                || event.amount() != evidence.confirmedAmount() || event.state() != evidence.confirmedState()) {
            throw failure("SELLER_CONFIRMATION_MISMATCH");
        }
        if (!TERMINAL_FEEDBACK.contains(event.state())) throw failure("RESULT_NOT_FINAL");
        return event;
    }

    private Result evaluate(PaymentOrder order, PayAppFeedback event, String reference, String expectedSnapshot) {
        if (!PaymentHashes.equal(order.getNotificationToken(), event.token()) || event.amount() != order.getPrice()
                || !"KRW".equals(order.getCurrency())) throw failure("ORDER_EVIDENCE_MISMATCH");
        if ((order.getProviderTransaction() != null && !order.getProviderTransaction().equals(event.transactionId()))
                || bindings.findById(event.transactionId())
                        .filter(binding -> !order.getId().equals(binding.getOrderId())).isPresent()) {
            throw failure("TRANSACTION_CONFLICT");
        }

        var prior = recoveries.findById(order.getId());
        if (prior.isPresent()) {
            var recovered = prior.get();
            if (!recovered.getEvidenceHash().equals(evidenceHash(event)) || !recovered.getOperatorReference().equals(reference)
                    || (expectedSnapshot != null && !recovered.getSnapshotHash().equals(expectedSnapshot))) {
                throw failure("RECOVERY_ALREADY_RECORDED");
            }
            return result("ALREADY_RECOVERED", order, null);
        }
        if (notifications.existsById(event.eventHash())) return result("ALREADY_RECORDED", order, null);

        var now = authClock.instant();
        boolean unconfirmed = order.getState() == PaymentOrder.State.UNKNOWN
                || order.getState() == PaymentOrder.State.PENDING
                || (order.getState() == PaymentOrder.State.REQUESTING && !order.getRequestDeadline().isAfter(now));
        if (!unconfirmed || order.getCreditGrantId() != null) throw failure("ORDER_NOT_UNCONFIRMED");

        var last = notifications.findFirstByOrderIdOrderByRespondedAtDescEventHashDesc(order.getId()).orElse(null);
        var earliest = order.getCreatedAt().toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).minusSeconds(1);
        if (event.respondedAt().isAfter(now.plusSeconds(120)) || event.respondedAt().isBefore(earliest)
                || (last != null && (event.respondedAt().isBefore(last.getRespondedAt())
                        || TERMINAL_FEEDBACK.contains(last.getStateCode())))) {
            throw failure("NOTIFICATION_TIMELINE_CONFLICT");
        }

        String snapshot = PaymentHashes.sha256(order.getPublicId() + ":" + order.getVersion() + ":"
                + evidenceHash(event) + ":" + reference + ":" + (last == null ? "" : last.getEventHash()));
        return result("READY", order, snapshot);
    }

    private static String evidenceHash(PayAppFeedback event) {
        return PaymentHashes.sha256(event.eventHash() + ":" + event.approvedAt());
    }

    private static Result result(String status, PaymentOrder order, String snapshot) {
        return new Result(status, order.getPublicId(), order.getState(), order.getCreditGrantId() != null, snapshot);
    }

    private static Failure failure(String code) { return new Failure(code); }

    public static final class Failure extends RuntimeException {
        Failure(String code) { super(code); }
    }

    public record Result(String status, String orderId, PaymentOrder.State orderState, boolean creditGranted,
            String snapshot) {}
}
