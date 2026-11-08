package com.mongle.backend.operations.payment;

import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class PaymentRefundCommand {
    private PaymentRefundCommand() {}

    public static boolean requested(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.startsWith("--payment-refund"));
    }

    public static int run(String[] args, Map<String, String> environment, PrintStream output) {
        var json = JsonMapper.builder().build();
        try (var ignored = new PaymentInspectLogging()) {
            var request = RefundResolutionRequest.parse(args);
            if (!"true".equals(environment.get("MONGLE_PAYMENT_REFUND_OPS_ENABLED"))) {
                throw new PaymentInspectCommand.Failure("OPERATOR_COMMAND_DISABLED");
            }
            var env = new HashMap<>(environment);
            env.put("MONGLE_PAYMENT_INSPECT_ENABLED", "true");
            env.put("MONGLE_PAYMENT_INSPECT_EXPECTED_HOST",
                    environment.getOrDefault("MONGLE_PAYMENT_REFUND_OPS_EXPECTED_HOST", ""));
            env.put("MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE",
                    environment.getOrDefault("MONGLE_PAYMENT_REFUND_OPS_EXPECTED_DATABASE", ""));
            var settings = PaymentInspectSettings.fromEnvironment(env);
            output.println(json.writeValueAsString(
                    new RefundResolver(settings.dataSource(), Clock.systemUTC()).execute(request)));
            return 0;
        } catch (Exception failure) {
            String code = switch (failure) {
                case PaymentInspectCommand.Failure known -> known.getMessage();
                case java.sql.SQLTimeoutException ignored -> "REFUND_RESOLUTION_TIMEOUT";
                default -> "REFUND_RESOLUTION_FAILED";
            };
            output.println(json.writeValueAsString(Map.of("event", "refund-resolution-failed", "code", code)));
            return 1;
        }
    }
}
