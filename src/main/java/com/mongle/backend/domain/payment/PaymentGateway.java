package com.mongle.backend.domain.payment;
import java.util.concurrent.CompletableFuture;
public interface PaymentGateway {
    boolean available();
    CompletableFuture<Result> request(Command command);
    record Command(String orderId, String token, String name, long price, String phone) {
        @Override public String toString() { return "PaymentCommand[redacted]"; }
    }
    enum Outcome { READY, REJECTED, UNKNOWN }
    record Result(Outcome outcome, String transactionId, String paymentUrl) {
        public static Result unknown() { return new Result(Outcome.UNKNOWN,null,null); }
        public static Result rejected() { return new Result(Outcome.REJECTED,null,null); }
        @Override public String toString() { return "PaymentResult["+outcome+"]"; }
    }
}
