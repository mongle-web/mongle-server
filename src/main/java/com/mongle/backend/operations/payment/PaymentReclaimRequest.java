package com.mongle.backend.operations.payment;

import java.util.*;

record PaymentReclaimRequest(
        String operation,
        String orderId,
        String requestKey,
        String reference,
        String expectedSnapshot) {
    static PaymentReclaimRequest parse(String[] args) {
        var values = new HashMap<String, String>();
        for (String arg : args) {
            int at = arg.indexOf('=');
            if (at < 1 || values.putIfAbsent(arg.substring(0, at), arg.substring(at + 1)) != null)
                throw new IllegalArgumentException();
        }
        String operation = values.get("--payment-reclaim");
        boolean apply = "apply".equals(operation);
        var keys =
                new HashSet<>(
                        Set.of(
                                "--payment-reclaim",
                                "--order-id",
                                "--request-key",
                                "--operator-reference"));
        if (apply) keys.add("--expected-snapshot");
        if (!values.keySet().equals(keys) || !Set.of("preview", "apply").contains(operation))
            throw new IllegalArgumentException();
        String id =
                PaymentInspectRequest.parse(
                                new String[] {
                                    "--payment-inspect=order",
                                    "--order-id=" + values.get("--order-id")
                                })
                        .orderId();
        for (String key : List.of("--request-key", "--operator-reference"))
            if (!values.get(key).matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,79}"))
                throw new IllegalArgumentException();
        if (apply && !values.get("--expected-snapshot").matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException();
        return new PaymentReclaimRequest(
                operation,
                id,
                values.get("--request-key"),
                values.get("--operator-reference"),
                values.get("--expected-snapshot"));
    }

    boolean apply() {
        return operation.equals("apply");
    }

    @Override
    public String toString() {
        return "PaymentReclaimRequest[redacted]";
    }
}
