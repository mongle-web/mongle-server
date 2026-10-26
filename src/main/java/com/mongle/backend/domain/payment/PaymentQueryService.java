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
    public PaymentOrderPage page(Long user,Long beforeId,int size) {
        if(size<1 || size>50 || beforeId!=null && beforeId<1) throw new BusinessException(PaymentErrorCode.INVALID_PAGE);
        requireUser(user);
        var rows=orders.page(user,beforeId,org.springframework.data.domain.PageRequest.of(0,size+1));
        boolean hasNext=rows.size()>size;
        var now=authClock.instant();
        return new PaymentOrderPage(rows.stream().limit(size).map(o -> PaymentOrderResponse.from(o,now)).toList(),
                hasNext,hasNext?rows.get(size-1).getId():null);
    }
    private void requireUser(Long id) {
        if(id==null || id<1) throw new BusinessException(PaymentErrorCode.INVALID_INPUT);
        var u=users.findById(id).orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND));
        if(!u.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }
}
