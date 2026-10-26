package com.mongle.backend.domain.payment;

import com.mongle.backend.domain.credit.CreditProperties;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.domain.user.exception.UserErrorCode;
import com.mongle.backend.global.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

@Service @RequiredArgsConstructor
public class PaymentTransactions {
    private final UserRepository users;
    private final PaymentOrderRepository orders;
    private final PaymentTransactionRepository bindings;
    private final PaymentCatalog catalog;
    private final PaymentProperties properties;
    private final CreditProperties creditProperties;
    private final com.mongle.backend.domain.credit.CreditService credits;
    private final Clock authClock;
    public record Begin(PaymentOrderResponse response,PaymentGateway.Command command) {}

    @Transactional
    public Begin begin(Long user,PaymentOrderRequest request,boolean providerAvailable,Runnable acquire) {
        if(request==null || request.requestId()==null || request.productId()==null
                || !request.productId().matches("[a-z][a-z0-9-]{0,49}")
                || request.buyerPhone()==null || !request.buyerPhone().matches("01[016789][0-9]{7,8}"))
            throw new BusinessException(PaymentErrorCode.INVALID_INPUT);
        requireUser(user);
        var prior=orders.findByUserIdAndRequestId(user,request.requestId().toString());
        if(prior.isPresent()) {
            if(!PaymentHashes.equal(prior.get().getInputHash(),PaymentOrder.inputHash(request)))
                throw new BusinessException(PaymentErrorCode.REQUEST_CONFLICT);
            return new Begin(PaymentOrderResponse.from(prior.get(),authClock.instant()),null);
        }
        if(!properties.enabled() || !creditProperties.enabled() || !providerAvailable)
            throw new BusinessException(PaymentErrorCode.UNAVAILABLE);
        if(credits.balance(user).spendingBlocked())
            throw new BusinessException(com.mongle.backend.domain.credit.CreditErrorCode.PAYMENT_REVIEW);
        var product=catalog.purchasable(request.productId());
        acquire.run();
        var order=orders.saveAndFlush(PaymentOrder.create(user,request,product,authClock.instant().plusSeconds(45)));
        return new Begin(PaymentOrderResponse.from(order,authClock.instant()),new PaymentGateway.Command(
                order.getPublicId(),order.getNotificationToken(),order.getProductName(),order.getPrice(),request.buyerPhone()));
    }
    @Transactional
    public PaymentOrderResponse finish(Long user,String publicId,PaymentGateway.Result result) {
        requireUser(user);
        var order=orders.findByPublicIdAndUserId(publicId,user)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND));
        if(result.outcome()==PaymentGateway.Outcome.READY) bind(order,result.transactionId());
        order.requestFinished(result);
        orders.flush();
        return PaymentOrderResponse.from(order,authClock.instant());
    }
    void bind(PaymentOrder order,String transactionId) {
        if(transactionId==null || !transactionId.matches("[1-9][0-9]{0,39}"))
            throw new BusinessException(PaymentErrorCode.TRANSACTION_CONFLICT);
        var prior=bindings.findById(transactionId);
        if(prior.isPresent() && !prior.get().getOrderId().equals(order.getId()))
            throw new BusinessException(PaymentErrorCode.TRANSACTION_CONFLICT);
        if(prior.isEmpty()) bindings.saveAndFlush(PaymentTransactionBinding.create(transactionId,order.getId()));
    }
    void requireUser(Long id) {
        if(id==null || id<1) throw new BusinessException(PaymentErrorCode.INVALID_INPUT);
        var u=users.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(PaymentErrorCode.NOT_FOUND));
        if(!u.isOnboardingCompleted()) throw new BusinessException(UserErrorCode.ONBOARDING_REQUIRED);
    }
}
