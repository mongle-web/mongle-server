package com.mongle.backend.operations.credit;

import com.mongle.backend.domain.credit.PromotionCreditOperations;

import java.util.HashMap;
import java.util.Set;

record TestCreditRequest(String operation, Long userId, long amount, String requestKey) {
    static TestCreditRequest parse(String[] arguments) {
        if (arguments.length != 4) throw new IllegalArgumentException();
        var values = new HashMap<String, String>();
        for (String argument : arguments) {
            int separator = argument.indexOf('=');
            if (separator < 1
                    || values.putIfAbsent(
                                    argument.substring(0, separator),
                                    argument.substring(separator + 1))
                            != null) throw new IllegalArgumentException();
        }
        if (!values.keySet()
                .equals(Set.of("--test-credit", "--user-id", "--amount", "--request-key")))
            throw new IllegalArgumentException();
        String operation = values.get("--test-credit");
        if (!Set.of("preview", "grant").contains(operation)) throw new IllegalArgumentException();
        var request =
                new TestCreditRequest(
                        operation,
                        Long.valueOf(values.get("--user-id")),
                        Long.parseLong(values.get("--amount")),
                        values.get("--request-key"));
        PromotionCreditOperations.validate(request.userId, request.amount, request.requestKey);
        return request;
    }
}
