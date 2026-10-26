package com.mongle.backend.domain.credit;

import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreditService {
    private final UserRepository users;
    private final CreditAccountRepository accounts;
    private final CreditGrantRepository grants;
    private final CreditLedgerRepository ledger;
    private final CreditProperties properties;
    private final CreditPaymentHoldRepository paymentHolds;

    public CreditBalance balance(Long userId) {
        requireUser(userId, false);
        var balance=accounts.findById(userId).map(CreditBalance::from).orElseGet(CreditBalance::zero);
        return new CreditBalance(balance.available(),balance.reserved(),balance.total(),paymentHolds.existsByUserId(userId));
    }

    public CreditTransactionPage transactions(Long userId, Long beforeId, int size) {
        if (size < 1 || size > 50 || (beforeId != null && beforeId < 1))
            throw new BusinessException(CreditErrorCode.INVALID_PAGE);
        requireUser(userId, false);
        var rows = ledger.page(userId, beforeId, org.springframework.data.domain.PageRequest.of(0, size + 1));
        boolean hasNext = rows.size() > size;
        var items = rows.stream().limit(size).map(CreditTransaction::from).toList();
        return new CreditTransactionPage(items, hasNext, hasNext ? items.getLast().transactionId() : null);
    }

    /** 결제 상태 저장과 같은 트랜잭션에 참여한다. 외부 결제 요청 중 호출하지 않는다. */
    @Transactional
    public CreditGrantReceipt grant(Long userId, CreditSource source, String sourceKey, long amount) {
        CreditRules.positive(amount);
        if (source == null) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        String hash = CreditRules.hash(sourceKey);
        requireUser(userId, true);
        var prior = grants.findBySourceTypeAndSourceHash(source, hash);
        if (prior.isPresent()) {
            var g = prior.get();
            if (!g.getUserId().equals(userId) || !g.getSourceKey().equals(sourceKey) || g.getAmount() != amount)
                throw new BusinessException(CreditErrorCode.REQUEST_CONFLICT);
            return CreditGrantReceipt.from(g);
        }
        properties.requireEnabled();
        var account = account(userId);
        account.grant(amount);
        var grant = grants.saveAndFlush(CreditGrant.create(userId, source, sourceKey, amount));
        ledger.save(CreditLedger.entry(account, CreditLedger.Kind.GRANT, grant.getId(), null, source, null, amount, 0));
        ledger.flush();
        return CreditGrantReceipt.from(grant);
    }

    /** 검증된 외부 취소 통보와 같은 트랜잭션. 기능 비활성화 뒤에도 이미 지급한 결제를 보호한다. */
    @Transactional
    public void holdPayment(Long userId,String sourceKey) {
        String hash=CreditRules.hash(sourceKey);
        requireUser(userId,true);
        var grant=grants.findBySourceTypeAndSourceHash(CreditSource.PAYMENT,hash)
                .orElseThrow(() -> new BusinessException(CreditErrorCode.NOT_FOUND));
        if(!grant.getUserId().equals(userId) || !grant.getSourceKey().equals(sourceKey))
            throw new BusinessException(CreditErrorCode.REQUEST_CONFLICT);
        if(paymentHolds.existsById(grant.getId())) return;
        paymentHolds.saveAndFlush(CreditPaymentHold.create(grant.getId(),userId));
        ledger.saveAndFlush(CreditLedger.entry(account(userId),CreditLedger.Kind.PAYMENT_HOLD,grant.getId(),null,CreditSource.PAYMENT,null,0,0));
    }
    void requireSpendingAllowed(Long userId) {
        if(paymentHolds.existsByUserId(userId)) throw new BusinessException(CreditErrorCode.PAYMENT_REVIEW);
    }
    CreditAccount account(Long userId) {
        return accounts.findById(userId).orElseGet(() -> accounts.save(CreditAccount.create(userId)));
    }
    void requireUser(Long userId, boolean lock) {
        if (userId == null || userId < 1) throw new BusinessException(CreditErrorCode.INVALID_INPUT);
        var user = (lock ? users.findByIdForUpdate(userId) : users.findById(userId))
                .orElseThrow(() -> new BusinessException(CreditErrorCode.NOT_FOUND));
        if (!user.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }
}
