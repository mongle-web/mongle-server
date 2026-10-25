package com.mongle.backend.domain.payment;

import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class PaymentQueryService {
    private final PaymentOrderRepository orders;
    private final UserRepository users;
    private final Clock authClock;
    public PaymentOrderResponse get(Long user,String publicId) {
        requireUser(user);
        return PaymentOrderResponse.from(orders.findByPublicIdAndUserId(publicId,user)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND)),authClock.instant());
    }
    private void requireUser(Long id) {
        if(id==null || id<1) throw new BusinessException(PaymentErrorCode.INVALID_INPUT);
        var u=users.findById(id).orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND));
        if(!u.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }
}
