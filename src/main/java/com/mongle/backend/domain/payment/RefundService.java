package com.mongle.backend.domain.payment;

import com.mongle.backend.domain.credit.CreditAccountRepository;
import com.mongle.backend.domain.credit.CreditGrant;
import com.mongle.backend.domain.credit.CreditGrantRepository;
import com.mongle.backend.domain.credit.CreditSource;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.withdrawal.WithdrawalRefundPolicy;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RefundService implements WithdrawalRefundPolicy {
    private final PaymentRefundRepository refunds;
    private final PaymentOrderRepository orders;
    private final CreditGrantRepository grants;
    private final CreditAccountRepository accounts;
    private final PaymentTransactions transactions;
    private final UserRepository users;
    private final RefundProperties properties;
    private final Clock authClock;

    public RefundEstimate estimate(Long userId, UUID orderId, PaymentRefundRequest.Reason reason) {
        requireActive(userId);
        requireEnabled();
        var order = ownedOrder(userId, orderId);
        var grant = purchaseGrant(order);
        if (refunds.countOpenOrCompletedForGrant(grant.getId()) > 0) throw new BusinessException(RefundError.CONFLICT);
        return RefundEstimate.calculate(order, grant, reason, authClock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RefundReceipt receive(Long userId, RefundRequest request) {
        // Shared lock with credit reservations, verified payment feedback, reclaim and withdrawal.
        transactions.requireUser(userId);
        var existing = refunds.findByUserIdAndRequestId(userId, request.requestId().toString());
        if (existing.isPresent()) {
            var receipt = existing.get();
            if (!receipt.getOrder().getPublicId().equals(request.orderId().toString())
                    || receipt.getReason() != request.reason()) {
                throw new BusinessException(RefundError.CONFLICT);
            }
            return receipt(receipt);
        }

        requireEnabled();
        var order = ownedOrder(userId, request.orderId());
        var grant = purchaseGrant(order);
        if (refunds.countOpenOrCompletedForGrant(grant.getId()) > 0) throw new BusinessException(RefundError.CONFLICT);

        var now = authClock.instant().truncatedTo(ChronoUnit.MICROS);
        var estimate = RefundEstimate.calculate(order, grant, request.reason(), now);
        if (!estimate.requestable()) throw new BusinessException(RefundError.INELIGIBLE);
        var refund = refunds.saveAndFlush(PaymentRefundRequest.receive(
                order, grant, request.requestId(), request.reason(), estimate, now));
        // Invalidate older repeatable-read snapshots that might still see this grant as spendable.
        if (accounts.invalidateSpendingSnapshot(userId) != 1) {
            throw new BusinessException(RefundError.INELIGIBLE);
        }
        return receipt(refund);
    }

    public RefundReceipt get(Long userId, UUID receiptId) {
        requireActive(userId);
        return refunds.findByUserIdAndReceiptId(userId, receiptId.toString())
                .map(this::receipt).orElseThrow(() -> new BusinessException(RefundError.NOT_FOUND));
    }

    public RefundPage page(Long userId, Long beforeId, int size) {
        requireActive(userId);
        if (size < 1 || size > 50 || (beforeId != null && beforeId < 1)) {
            throw new BusinessException(RefundError.INVALID);
        }
        var rows = refunds.page(userId, beforeId, PageRequest.of(0, size + 1));
        boolean hasNext = rows.size() > size;
        return new RefundPage(rows.stream().limit(size).map(this::receipt).toList(),
                hasNext, hasNext ? rows.get(size - 1).getId() : null);
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public boolean coversRemainingPurchases(Long userId) {
        return refunds.uncoveredPaidGrants(userId) == 0;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public boolean hasOpenRefunds(Long userId) {
        return refunds.countOpen(userId) > 0;
    }

    private PaymentOrder ownedOrder(Long userId, UUID orderId) {
        return orders.findByPublicIdAndUserId(orderId.toString(), userId)
                .orElseThrow(() -> new BusinessException(RefundError.NOT_FOUND));
    }

    private CreditGrant purchaseGrant(PaymentOrder order) {
        if ((order.getState() != PaymentOrder.State.PAID && order.getState() != PaymentOrder.State.PAYMENT_REVIEW)
                || order.getCreditGrantId() == null || order.getPrice() <= 0 || order.getCredits() <= 0
                || !"KRW".equals(order.getCurrency())) {
            throw new BusinessException(RefundError.INELIGIBLE);
        }
        var grant = grants.findById(order.getCreditGrantId())
                .orElseThrow(() -> new BusinessException(RefundError.INELIGIBLE));
        long available = grant.getAvailable();
        long reserved = grant.getReserved();
        if (!grant.getUserId().equals(order.getUserId()) || grant.getSourceType() != CreditSource.PAYMENT
                || !grant.getSourceKey().equals(PaymentFeedbackService.sourceKey(order))
                || grant.getAmount() != order.getCredits() || grant.getForfeited() != 0
                || available < 0 || reserved < 0 || available > grant.getAmount()
                || reserved > grant.getAmount() - available
                || grant.getConsumed() != grant.getAmount() - available - reserved) {
            throw new BusinessException(RefundError.INELIGIBLE);
        }
        return grant;
    }

    private RefundReceipt receipt(PaymentRefundRequest row) {
        var order = row.getOrder();
        var resolution = row.getResolution();
        var estimate = resolution == null
                ? RefundEstimate.calculate(order, row.getGrant(), row.getReason(), row.getCreatedAt()) : null;
        var status = resolution == null ? RefundReceipt.ProcessingStatus.RECEIVED
                : RefundReceipt.ProcessingStatus.valueOf(resolution.getStatus().name());
        var result = resolution == null ? null : new RefundReceipt.Resolution(
                resolution.getRefundedAmount(), resolution.getRevokedCredits(), resolution.getResolvedAt());
        return new RefundReceipt(row.getReceiptId(), order.getPublicId(), row.getRequestId(), row.getCreatedAt(), row.getReason(),
                row.getInitialStatus(), row.getAvailableAtRequest(), row.getReservedAtRequest(),
                row.getUsedAtRequest(), row.getEstimatedAmount(), row.getFeePercent(), estimate, properties.supportEmail(), status, result);
    }

    private void requireActive(Long userId) {
        var user = users.findById(userId).filter(u -> !u.isWithdrawn())
                .orElseThrow(() -> new BusinessException(RefundError.NOT_FOUND));
        if (!user.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }

    private void requireEnabled() {
        if (!properties.enabled()) throw new BusinessException(RefundError.UNAVAILABLE);
    }
}
