package com.mongle.backend.domain.payment;

import com.mongle.backend.global.error.BusinessException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class PaymentService {
    private final PaymentTransactions transactions;
    private final PaymentGateway gateway;
    private final Semaphore slots=new Semaphore(8);
    // 외부 HTTP 대기는 이 풀을 점유하지 않는다. 완료 후 짧은 DB 반영만 수행한다.
    private final ThreadPoolExecutor completions=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),Thread.ofPlatform().name("payment-completion-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    public PaymentService(PaymentTransactions transactions,PaymentGateway gateway) {
        this.transactions=transactions; this.gateway=gateway;
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NEVER)
    public CompletableFuture<PaymentOrderResponse> create(Long user,PaymentOrderRequest request) {
        var acquired=new AtomicBoolean();
        final PaymentTransactions.Begin begin;
        try {
            begin=transactions.begin(user,request,!closed && gateway.available(),() -> {
                if(!slots.tryAcquire()) throw new BusinessException(PaymentErrorCode.CAPACITY_EXCEEDED);
                acquired.set(true);
            });
        } catch(RuntimeException ex) { if(acquired.get()) slots.release(); throw ex; }
        if(begin.command()==null) return CompletableFuture.completedFuture(begin.response());
        CompletableFuture<PaymentGateway.Result> call;
        try { call=gateway.request(begin.command()); }
        catch(RuntimeException ex) { call=CompletableFuture.completedFuture(PaymentGateway.Result.unknown()); }
        var persistence=call.handleAsync((result,error) -> transactions.finish(user,begin.response().orderId(),
                error==null && result!=null ? result : PaymentGateway.Result.unknown()),completions);
        persistence.whenComplete((r,e) -> slots.release());
        // MVC의 취소/연결 종료가 주문 결과 저장을 취소하지 않도록 반환 Future를 분리한다.
        return persistence.thenApply(r -> r);
    }
    @PreDestroy void close() {
        closed=true; completions.shutdown();
        try { if(!completions.awaitTermination(5,TimeUnit.SECONDS)) completions.shutdownNow(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); completions.shutdownNow(); }
    }
}
