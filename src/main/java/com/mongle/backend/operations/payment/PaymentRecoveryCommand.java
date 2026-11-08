package com.mongle.backend.operations.payment;

import com.mongle.backend.domain.credit.CreditProperties;
import com.mongle.backend.domain.payment.PayAppProperties;
import com.mongle.backend.domain.payment.PaymentRecoveryEvidence;
import com.mongle.backend.domain.payment.PaymentRecoveryService;
import com.mongle.backend.global.error.BusinessException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

public final class PaymentRecoveryCommand {
    private static final int MAX_INPUT_BYTES = 65_536;

    private PaymentRecoveryCommand() {}

    public static boolean requested(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> arg.startsWith("--payment-recovery"));
    }

    public static int run(String[] args, Map<String, String> env, InputStream input, PrintStream output) {
        var json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        try (var ignored = new PaymentInspectLogging()) {
            var request = Request.parse(args);
            if (!"true".equals(env.get("MONGLE_PAYMENT_RECOVERY_ENABLED")))
                throw new PaymentInspectCommand.Failure("OPERATOR_COMMAND_DISABLED");
            var dbEnv = new HashMap<>(env);
            dbEnv.put("MONGLE_PAYMENT_INSPECT_ENABLED", "true");
            dbEnv.put("MONGLE_PAYMENT_INSPECT_EXPECTED_HOST", env.get("MONGLE_PAYMENT_RECOVERY_EXPECTED_HOST"));
            dbEnv.put("MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE", env.get("MONGLE_PAYMENT_RECOVERY_EXPECTED_DATABASE"));
            var source = PaymentInspectSettings.fromEnvironment(dbEnv).dataSource();
            var payApp = payApp(env);
            var evidence = evidence(json, input);
            boolean creditEnabled = "true".equals(env.get("MONGLE_CREDIT_ENABLED"));
            var credits = new CreditProperties(creditEnabled, CreditProperties.ExpiryPolicy.NO_EXPIRY,
                    null, null, null, null, null);
            try (var context = PaymentRecoveryContext.open(source, payApp, credits, Clock.systemUTC())) {
                var recovery = context.getBean(PaymentRecoveryService.class);
                var result = request.snapshot() == null
                        ? recovery.preview(request.orderId(), request.reference(), evidence)
                        : recovery.apply(request.orderId(), request.reference(), request.snapshot(), evidence);
                output.println(json.writeValueAsString(result));
                return 0;
            }
        } catch (Exception failure) {
            String code = switch (failure) {
                case PaymentInspectCommand.Failure known -> known.getMessage();
                case PaymentRecoveryService.Failure known -> known.getMessage();
                case BusinessException business -> business.getErrorCode().getCode();
                default -> "PAYMENT_RECOVERY_FAILED";
            };
            output.println(json.writeValueAsString(Map.of("event", "payment-recovery-failed", "code", code)));
            return 1;
        }
    }

    private static PayAppProperties payApp(Map<String, String> env) {
        try {
            if (!"true".equals(env.get("MONGLE_PAYAPP_ENABLED"))) throw new IllegalArgumentException();
            return new PayAppProperties(true, env.get("PAYAPP_SELLER_ID"), env.get("PAYAPP_LINK_KEY"),
                    env.get("PAYAPP_LINK_VALUE"), env.get("PAYAPP_REQUEST_KEY"),
                    URI.create(env.get("PAYAPP_FEEDBACK_URL")), URI.create(env.get("PAYAPP_RETURN_URL")),
                    List.of("card"), Duration.ofSeconds(3), Duration.ofSeconds(30));
        } catch (RuntimeException invalid) {
            throw new PaymentInspectCommand.Failure("INVALID_PAYAPP_CONFIGURATION");
        }
    }

    private static PaymentRecoveryEvidence evidence(JsonMapper json, InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length > MAX_INPUT_BYTES) throw new IllegalArgumentException();
            var node = json.readTree(bytes);
            if (!node.isObject() || node.size() != 4 || !node.path("originalForm").isString()
                    || !node.path("confirmedTransaction").isString()
                    || !node.path("confirmedState").isIntegralNumber() || !node.path("confirmedState").canConvertToInt()
                    || !node.path("confirmedAmount").isIntegralNumber() || !node.path("confirmedAmount").canConvertToLong()) {
                throw new IllegalArgumentException();
            }
            String form = node.path("originalForm").asString();
            String transaction = node.path("confirmedTransaction").asString();
            long amount = node.path("confirmedAmount").asLong();
            if (form.isBlank() || form.getBytes(StandardCharsets.UTF_8).length > 32768
                    || !transaction.matches("[1-9][0-9]{0,39}") || amount < 1 || amount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException();
            }
            return new PaymentRecoveryEvidence(form, transaction, node.path("confirmedState").asInt(), amount);
        } catch (Exception invalid) {
            throw new PaymentInspectCommand.Failure("INVALID_RECOVERY_EVIDENCE");
        }
    }

    private record Request(String orderId, String reference, String snapshot) {
        static Request parse(String[] args) {
            try {
                var values = new HashMap<String, String>();
                for (String arg : args) {
                    int split = arg.indexOf('=');
                    if (split < 1 || values.putIfAbsent(arg.substring(0, split), arg.substring(split + 1)) != null)
                        throw new IllegalArgumentException();
                }
                String mode = values.remove("--payment-recovery");
                String id = values.remove("--order-id");
                String reference = values.remove("--operator-reference");
                String snapshot = "apply".equals(mode) ? values.remove("--expected-snapshot") : null;
                if (!values.isEmpty() || !Set.of("preview", "apply").contains(mode)
                        || !UUID.fromString(id).toString().equals(id)
                        || !reference.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,79}")
                        || ("apply".equals(mode) && !snapshot.matches("[0-9a-f]{64}")))
                    throw new IllegalArgumentException();
                return new Request(id, reference, snapshot);
            } catch (RuntimeException invalid) {
                throw new PaymentInspectCommand.Failure("INVALID_ARGUMENTS");
            }
        }
    }
}
