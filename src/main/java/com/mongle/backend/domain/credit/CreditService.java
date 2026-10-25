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

    public CreditBalance balance(Long userId) {
        requireUser(userId, false);
        return accounts.findById(userId).map(CreditBalance::from).orElseGet(CreditBalance::zero);
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
