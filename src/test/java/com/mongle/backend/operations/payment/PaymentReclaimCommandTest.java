package com.mongle.backend.operations.payment;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;

class PaymentReclaimCommandTest {
    String[] args(String mode) {
        return new String[] {
            "--payment-reclaim=" + mode,
            "--order-id=" + PaymentInspectCommandTest.ORDER,
            "--request-key=ops-119",
            "--operator-reference=case-119"
        };
    }

    void failure(String[] args, Map<String, String> env, String code) {
        var bytes = new ByteArrayOutputStream();
        assertThat(PaymentReclaimCommand.run(args, env, new PrintStream(bytes))).isEqualTo(1);
        assertThat(bytes.toString())
                .contains("\"code\":\"" + code + "\"")
                .doesNotContain("private-db-password", "case-119", "ops-119", "Started Mongle");
        assertThat(bytes.toString().lines().count()).isEqualTo(1);
    }

    @Test
    void exactArgumentsRequiredAndMixedModesNeverWriteOrStartServer() {
        assertThat(PaymentReclaimRequest.parse(args("preview")).operation()).isEqualTo("preview");
        for (String mode : List.of("", "grant", "refund", "release-hold", "apply"))
            failure(args(mode), Map.of(), "INVALID_ARGUMENTS");
        var mixed = new ArrayList<>(List.of(args("preview")));
        mixed.add("--test-credit=grant");
        failure(mixed.toArray(String[]::new), Map.of(), "INVALID_ARGUMENTS");
        mixed.set(4, "--payment-inspect=order");
        failure(mixed.toArray(String[]::new), Map.of(), "INVALID_ARGUMENTS");
        var bad = args("preview");
        bad[3] = "--operator-reference=private@example.com";
        failure(bad, Map.of(), "INVALID_ARGUMENTS");
        bad = args("preview");
        bad[2] = "--order-id=" + PaymentInspectCommandTest.ORDER;
        failure(bad, Map.of(), "INVALID_ARGUMENTS");
        assertThat(PaymentReclaimCommand.requested(new String[] {"--payment-reclaim-typo=apply"}))
                .isTrue();
    }

    @Test
    void disabledByDefaultAndDedicatedTargetSettingsRequired() {
        failure(args("preview"), Map.of(), "OPERATOR_COMMAND_DISABLED");
        var env = PaymentInspectCommandTest.environment();
        env.put("MONGLE_PAYMENT_RECLAIM_ENABLED", "true");
        failure(args("preview"), env, "INVALID_CONFIGURATION");
        env.put("MONGLE_PAYMENT_RECLAIM_EXPECTED_HOST", "127.0.0.1");
        env.put("MONGLE_PAYMENT_RECLAIM_EXPECTED_DATABASE", "mongle");
        env.put("DB_URL", "jdbc:mysql://127.0.0.1:1/mongle");
        failure(args("preview"), env, "DB_CONNECTION_FAILED");
    }
}
