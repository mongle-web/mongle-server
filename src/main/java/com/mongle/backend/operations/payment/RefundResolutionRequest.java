package com.mongle.backend.operations.payment;

import java.util.*;

record RefundResolutionRequest(Mode mode, String receiptId, Action action, long amount, long credits,
        String basis, String reference, String expectedSnapshot) {
    enum Mode { INSPECT, PREVIEW, APPLY }
    enum Action { SETTLE, REJECT, WITHDRAW }

    static RefundResolutionRequest parse(String[] args) {
        try {
            var values = new HashMap<String, String>();
            for (String arg : args) {
                int at = arg.indexOf('=');
                if (at < 1 || values.putIfAbsent(arg.substring(0, at), arg.substring(at + 1)) != null) {
                    throw new IllegalArgumentException();
                }
            }
            Mode mode = Mode.valueOf(values.remove("--payment-refund").toUpperCase(Locale.ROOT));
            String id = values.remove("--receipt-id");
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            if (mode == Mode.INSPECT && values.isEmpty()) {
                return new RefundResolutionRequest(mode, id, null, 0, 0, null, null, null);
            }
            if (mode == Mode.INSPECT) throw new IllegalArgumentException();
            Action action = Action.valueOf(values.remove("--action").toUpperCase(Locale.ROOT));
            String reference = values.remove("--operator-reference");
            if (!reference.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,79}")) throw new IllegalArgumentException();
            String token = mode == Mode.APPLY ? values.remove("--expected-snapshot") : null;
            if (mode == Mode.APPLY && !token.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            long amount = 0;
            long credits = 0;
            String basis = "NONE";
            if (action == Action.SETTLE) {
                amount = nonnegative(values.remove("--amount"));
                credits = nonnegative(values.remove("--credits"));
                basis = values.remove("--basis");
                if (amount == 0 || !Set.of("STANDARD", "REVIEW").contains(basis)) throw new IllegalArgumentException();
            }
            if (!values.isEmpty()) throw new IllegalArgumentException();
            return new RefundResolutionRequest(mode, id, action, amount, credits, basis, reference, token);
        } catch (RuntimeException failure) {
            throw new PaymentInspectCommand.Failure("INVALID_ARGUMENTS");
        }
    }

    private static long nonnegative(String value) {
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }

    boolean apply() { return mode == Mode.APPLY; }

    String status() {
        return switch (action) {
            case SETTLE -> "COMPLETED";
            case REJECT -> "REJECTED";
            case WITHDRAW -> "WITHDRAWN";
        };
    }

    @Override
    public String toString() { return "RefundResolutionRequest[redacted]"; }
}
