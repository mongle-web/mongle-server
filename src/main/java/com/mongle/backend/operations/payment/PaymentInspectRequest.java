package com.mongle.backend.operations.payment;

import java.util.*;

record PaymentInspectRequest(String orderId) {
    static PaymentInspectRequest parse(String[] arguments) {
        if (arguments.length != 2) throw new IllegalArgumentException();
        var values = new HashMap<String, String>();
        for (String argument : arguments) {
            int split = argument.indexOf('=');
            if (split < 1
                    || values.putIfAbsent(
                                    argument.substring(0, split), argument.substring(split + 1))
                            != null) throw new IllegalArgumentException();
        }
        if (!values.keySet().equals(Set.of("--payment-inspect", "--order-id"))
                || !"order".equals(values.get("--payment-inspect")))
            throw new IllegalArgumentException();
        String id = values.get("--order-id");
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        return new PaymentInspectRequest(id);
    }
}
