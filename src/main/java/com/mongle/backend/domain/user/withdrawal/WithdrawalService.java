package com.mongle.backend.domain.user.withdrawal;

import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;

@Service @RequiredArgsConstructor
public class WithdrawalService {
    private final UserRepository users;
    private final WithdrawalProperties properties;
    private final WithdrawalRefundPolicy refunds;
    private final WithdrawalData data;
    private final WithdrawalIdentityService identities;
    private final Clock authClock;

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void withdraw(Long userId) {
        if(!properties.enabled()) throw new BusinessException(UserErrorCode.WITHDRAWAL_UNAVAILABLE);
        var user=users.findByIdForUpdate(userId)
                .orElseThrow(()->new BusinessException(UserErrorCode.USER_NOT_FOUND));
        if(user.isWithdrawn()) return;
        if(data.paymentUnconfirmed(userId)) throw new BusinessException(UserErrorCode.PAYMENT_UNCONFIRMED);
        if(data.paidRemaining(userId)>0 && !refunds.coversRemainingPurchases(userId))
            throw new BusinessException(UserErrorCode.REFUND_REQUIRED);
        var now=LocalDateTime.ofInstant(authClock.instant(),ZoneOffset.UTC);
        boolean financial=data.hasFinancialRecords(userId);
        var until=financial?data.financialRetention(userId,now):now;
        identities.withdraw(userId);
        if(financial) data.releaseAndForfeitCredits(userId,now);
        else data.deleteAccounting(userId,now);
        data.deleteContents(userId,now);
        users.findByIdForUpdate(userId).orElseThrow().withdraw(now,until);
        users.flush();
    }
}
