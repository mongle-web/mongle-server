package com.mongle.backend.operations.payment;

import java.util.*;

record RefundResolutionRequest(Mode mode, String receiptId, Action action, long amount, long credits,
        String basis, String reference, String expectedSnapshot) {
    enum Mode { INSPECT, LIST_FOLLOW_UPS, INSPECT_FOLLOW_UP, PREVIEW, APPLY }
    enum Action { SETTLE, REJECT, WITHDRAW, FOLLOW_UP }

    static RefundResolutionRequest parse(String[] args) {
        try {
            var values = new HashMap<String, String>();
            for (String arg : args) {
                int at = arg.indexOf('=');
                if (at < 1 || values.putIfAbsent(arg.substring(0, at), arg.substring(at + 1)) != null) {
                    throw new IllegalArgumentException();
                }
            }
            Mode mode = Mode.valueOf(values.remove("--payment-refund").toUpperCase(Locale.ROOT).replace('-', '_'));
            if (mode == Mode.LIST_FOLLOW_UPS) {
                String cursor = values.remove("--after-receipt-id");
                if (!values.isEmpty()) throw new IllegalArgumentException();
                return new RefundResolutionRequest(mode, cursor == null ? "" : canonicalId(cursor), null, 0, 0, null, null, null);
            }
            String id = canonicalId(values.remove("--receipt-id"));
            if (mode == Mode.INSPECT || mode == Mode.INSPECT_FOLLOW_UP) {
                if (!values.isEmpty()) throw new IllegalArgumentException();
                return new RefundResolutionRequest(mode, id, null, 0, 0, null, null, null);
            }
            Action action = Action.valueOf(values.remove("--action").toUpperCase(Locale.ROOT).replace('-', '_'));
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
            if (action == Action.FOLLOW_UP) {
                amount = nonnegative(values.remove("--amount"));
                if (amount == 0) throw new IllegalArgumentException();
            }
            if (!values.isEmpty()) throw new IllegalArgumentException();
            return new RefundResolutionRequest(mode, id, action, amount, credits, basis, reference, token);
        } catch (RuntimeException failure) {
            throw new PaymentInspectCommand.Failure("INVALID_ARGUMENTS");
        }
    }

    private static String canonicalId(String id) {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        return id;
    }

    boolean followUp() { return mode == Mode.INSPECT_FOLLOW_UP || action == Action.FOLLOW_UP; }

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
            case FOLLOW_UP -> throw new IllegalStateException();
        };
    }

    @Override
    public String toString() { return "RefundResolutionRequest[redacted]"; }
}
