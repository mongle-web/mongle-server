package com.mongle.backend.global.logging;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** DB 상태 변경 성공 로그를 실제 커밋 뒤에 기록한다. 콜백에는 DB 작업을 넣지 않는다. */
public final class CommittedLog {
    private CommittedLog() { }

    public static void afterCommit(Runnable logging) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            // 서비스가 정상 반환해도 바깥 트랜잭션이 롤백될 수 있어 커밋 확정까지 기다린다.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { logging.run(); }
            });
        } else {
            // 트랜잭션이 없는 실행에서는 기다릴 커밋이 없으므로 바로 기록한다.
            logging.run();
        }
    }
}
