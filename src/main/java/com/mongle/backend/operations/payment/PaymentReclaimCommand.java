package com.mongle.backend.operations.payment;

import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.time.Clock;
import java.util.*;

public final class PaymentReclaimCommand {
    private PaymentReclaimCommand() {}

    public static boolean requested(String[] args) {
        return Arrays.stream(args).anyMatch(a -> a.startsWith("--payment-reclaim"));
    }

    public static int run(String[] args, Map<String, String> environment, PrintStream output) {
        var json = JsonMapper.builder().build();
        try (var ignored = new PaymentInspectLogging()) {
            PaymentReclaimRequest request;
            try {
                request = PaymentReclaimRequest.parse(args);
            } catch (RuntimeException failure) {
                throw new PaymentInspectCommand.Failure("INVALID_ARGUMENTS");
            }
            if (!"true".equals(environment.get("MONGLE_PAYMENT_RECLAIM_ENABLED")))
                throw new PaymentInspectCommand.Failure("OPERATOR_COMMAND_DISABLED");
            var env = new HashMap<>(environment);
            env.put("MONGLE_PAYMENT_INSPECT_ENABLED", "true");
            env.put(
                    "MONGLE_PAYMENT_INSPECT_EXPECTED_HOST",
                    environment.getOrDefault("MONGLE_PAYMENT_RECLAIM_EXPECTED_HOST", ""));
            env.put(
                    "MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE",
                    environment.getOrDefault("MONGLE_PAYMENT_RECLAIM_EXPECTED_DATABASE", ""));
            var settings = PaymentInspectSettings.fromEnvironment(env);
            output.println(
                    json.writeValueAsString(
                            new PaymentReclaimer(settings.dataSource(), Clock.systemUTC())
                                    .execute(request)));
            return 0;
        } catch (Exception failure) {
            String code =
                    failure instanceof PaymentInspectCommand.Failure
                            ? failure.getMessage()
                            : failure instanceof java.sql.SQLIntegrityConstraintViolationException
                                    ? "RECLAMATION_WRITE_CONFLICT"
                                    : failure instanceof java.sql.SQLTimeoutException
                                            ? "RECLAMATION_TIMEOUT"
                                            : "PAYMENT_RECLAMATION_FAILED";
            output.println(
                    json.writeValueAsString(
                            Map.of("event", "payment-reclamation-failed", "code", code)));
            return 1;
        }
    }
}
