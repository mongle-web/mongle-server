package com.mongle.backend.domain.user.withdrawal;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.*;

@Slf4j
public final class WithdrawalWorker implements AutoCloseable {
    private final WithdrawalProperties properties;
    private final UnlinkTransactions unlink;
    private final KakaoUnlinkGateway gateway;
    private final WithdrawalSecrets secrets;
    private final WithdrawalRetention retention;
    private long retentionCursor;
    private final ScheduledThreadPoolExecutor executor=new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().name("withdrawal-worker-",0).factory());
    public WithdrawalWorker(WithdrawalProperties properties,UnlinkTransactions unlink,KakaoUnlinkGateway gateway,
            WithdrawalSecrets secrets,WithdrawalRetention retention) {
        this.properties=properties; this.unlink=unlink; this.gateway=gateway; this.secrets=secrets; this.retention=retention;
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if(properties.workerEnabled())
            executor.scheduleWithFixedDelay(this::poll,0,properties.pollInterval().toMillis(),TimeUnit.MILLISECONDS);
    }
    public synchronized void poll() {
        if(!properties.workerEnabled()) return;
        try {
            if(gateway.available()) for(String hash:unlink.candidates()) unlink.claim(hash).ifPresent(claim->{
                boolean success=false;
                try { success=gateway.unlink(secrets.decrypt(claim.encryptedProviderId())); }
                catch(RuntimeException e) { log.warn("카카오 연결 해제 대상 확인 실패"); }
                unlink.finish(claim,success);
            });
            var candidates=retention.candidates(retentionCursor);
            if(candidates.isEmpty()) retentionCursor=0;
            for(Long user:candidates) {
                retentionCursor=user; // 미완료 환불/정리 건이 앞에 있어도 뒤의 만료 기록을 굶기지 않는다.
                retention.purge(user);
            }
        } catch(RuntimeException e) { log.warn("탈퇴 후속 작업 재시도 필요: errorType={}",e.getClass().getSimpleName()); }
    }
    @Override public void close() { executor.shutdownNow(); }
}
