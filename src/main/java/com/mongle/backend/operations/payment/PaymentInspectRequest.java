package com.mongle.backend.operations.payment;

import java.util.*;

record PaymentInspectRequest(Mode mode, String orderId) {
    enum Mode { ORDER, UNCONFIRMED }
    static PaymentInspectRequest parse(String[] arguments) {
        var values = new HashMap<String, String>();
        for (String argument : arguments) {
            int split = argument.indexOf('=');
            if (split < 1
                    || values.putIfAbsent(
                                    argument.substring(0, split), argument.substring(split + 1))
                            != null) throw new IllegalArgumentException();
        }
        String mode = values.remove("--payment-inspect");
        if ("order".equals(mode) && values.keySet().equals(Set.of("--order-id"))) {
            return new PaymentInspectRequest(Mode.ORDER, canonical(values.get("--order-id")));
        }
        if ("unconfirmed".equals(mode) && (values.isEmpty() || values.keySet().equals(Set.of("--after-order-id")))) {
            return new PaymentInspectRequest(Mode.UNCONFIRMED,
                    values.isEmpty() ? "" : canonical(values.get("--after-order-id")));
        }
        throw new IllegalArgumentException();
    }

    private static String canonical(String id) {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        return id;
    }
}
