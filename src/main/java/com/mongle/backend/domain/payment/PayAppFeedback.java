package com.mongle.backend.domain.payment;
import java.time.Instant;
/** 인증 필드·전화번호·원문 통보는 영속하거나 로그에 남기지 않는다. */
record PayAppFeedback(String orderId,String token,String transactionId,int state,long amount,
        String originalTransaction,Long originalAmount,Instant respondedAt,String eventHash) {
    boolean partialCancellation() { return state==70 || state==71; }
    boolean cancellation() { return partialCancellation() || state==8 || state==32 || state==9 || state==64; }
    @Override public String toString() { return "PayAppFeedback[redacted]"; }
}
