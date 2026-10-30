package com.mongle.backend.operations.payment;

import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.time.Clock;
import java.util.*;

public final class PaymentInspectCommand {
    private PaymentInspectCommand() {}

    public static boolean requested(String[] arguments) {
        return Arrays.stream(arguments).anyMatch(value -> value.startsWith("--payment-inspect"));
    }

    public static int run(String[] arguments, Map<String, String> environment, PrintStream output) {
        var json = JsonMapper.builder().build();
        try (var ignored = new PaymentInspectLogging()) {
            PaymentInspectRequest request;
            try {
                request = PaymentInspectRequest.parse(arguments);
            } catch (RuntimeException failure) {
                throw new Failure("INVALID_ARGUMENTS");
            }
            var settings = PaymentInspectSettings.fromEnvironment(environment);
            var report =
                    new PaymentInspector(settings.dataSource(), Clock.systemUTC())
                            .inspect(request.orderId());
            output.println(json.writeValueAsString(report));
            return 0;
        } catch (Exception failure) {
            String code =
                    failure instanceof Failure ? failure.getMessage() : "PAYMENT_INSPECTION_FAILED";
            output.println(
                    json.writeValueAsString(
                            Map.of("event", "payment-inspection-failed", "code", code)));
            return 1;
        }
    }

    static final class Failure extends RuntimeException {
        Failure(String code) {
            super(code);
        }
    }
}
