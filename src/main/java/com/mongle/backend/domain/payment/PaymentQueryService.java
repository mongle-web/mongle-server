package com.mongle.backend.domain.payment;

import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Service @RequiredArgsConstructor @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class PaymentQueryService {
    private final PaymentOrderRepository orders;
    private final UserRepository users;
    private final PaymentRefundRepository refunds;
    private final Clock authClock;
    public PaymentOrderResponse byRequestId(Long user,String requestId) {
        requireUser(user);
        final String canonical;
        try {
            canonical=java.util.UUID.fromString(requestId).toString();
            if(!canonical.equalsIgnoreCase(requestId)) throw new IllegalArgumentException();
        } catch(IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(PaymentErrorCode.INVALID_INPUT);
        }
        return response(orders.findByUserIdAndRequestId(user,canonical)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND)));
    }
    public PaymentOrderResponse get(Long user,String publicId) {
        requireUser(user);
        return response(orders.findByPublicIdAndUserId(publicId,user)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND)));
    }
    public PaymentOrderPage page(Long user,Long beforeId,int size) {
        if(size<1 || size>50 || beforeId!=null && beforeId<1) throw new BusinessException(PaymentErrorCode.INVALID_PAGE);
        requireUser(user);
        var rows=orders.page(user,beforeId,org.springframework.data.domain.PageRequest.of(0,size+1));
        boolean hasNext=rows.size()>size;
        var now=authClock.instant();
        var page = rows.subList(0, Math.min(size, rows.size()));
        var totals = refunds.confirmedCancellationAmounts(page.stream().map(PaymentOrder::getId).toList());
        return new PaymentOrderPage(page.stream().map(o -> PaymentOrderResponse.from(o,now,totals.get(o.getId()))).toList(),
                hasNext,hasNext?rows.get(size-1).getId():null);
    }

    private PaymentOrderResponse response(PaymentOrder order) {
        return PaymentOrderResponse.from(order, authClock.instant(), refunds.confirmedCancellationAmount(order.getId()));
    }
    private void requireUser(Long id) {
        if(id==null || id<1) throw new BusinessException(PaymentErrorCode.INVALID_INPUT);
        var u=users.findById(id).orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND));
        if(!u.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }
}
