package com.mongle.backend.operations.payment;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.util.*;

class PaymentInspectCommandTest {
    static final String ORDER = "c2ec49bb-333f-4a79-8c2b-4c8ae760e610";

    static String[] arguments() {
        return new String[] {"--payment-inspect=order", "--order-id=" + ORDER};
    }

    static Map<String, String> environment() {
        return new HashMap<>(
                Map.of(
                        "MONGLE_PAYMENT_INSPECT_ENABLED",
                        "true",
                        "MONGLE_PAYMENT_INSPECT_EXPECTED_HOST",
                        "db.example.invalid",
                        "MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE",
                        "mongle",
                        "DB_URL",
                        "jdbc:mysql://db.example.invalid:3306/mongle",
                        "DB_USERNAME",
                        "mongle_app",
                        "DB_PASSWORD",
                        "private-db-password",
                        "DB_TRUSTSTORE_URL",
                        "file:/etc/mongle/rds-truststore.p12",
                        "DB_TRUSTSTORE_PASSWORD",
                        "private-trust-password"));
    }

    @Test
    void acceptsOnlyOneExplicitOrderUuidWithoutWriteOrOtherCommandArguments() {
        assertThat(PaymentInspectRequest.parse(arguments()).orderId()).isEqualTo(ORDER);
        assertThat(
                        PaymentInspectRequest.parse(new String[] {arguments()[1], arguments()[0]})
                                .orderId())
                .isEqualTo(ORDER);
        var cases = new ArrayList<String[]>();
        cases.add(new String[] {"--payment-inspect=order"});
        cases.add(
                new String[] {
                    "--payment-inspect=order", "--order-id=" + ORDER, "--test-credit=grant"
                });
        cases.add(new String[] {"--payment-inspect=order", "--db-migration=migrate"});
        cases.add(new String[] {"--payment-inspect=order", "--payment-inspect=order"});
        for (String value : List.of("", "grant", "refund", "release-hold", "order=extra"))
            cases.add(new String[] {"--payment-inspect=" + value, "--order-id=" + ORDER});
        for (String id :
                List.of("", "1", "' OR 1=1", ORDER.toUpperCase(), "1-1-1-1-1", ORDER + "\nprivate"))
            cases.add(new String[] {"--payment-inspect=order", "--order-id=" + id});
        for (var args : cases) assertFailure(args, environment(), "INVALID_ARGUMENTS");
        assertThat(PaymentInspectCommand.requested(new String[] {"--payment-inspect-typo=order"}))
                .isTrue();
        assertThat(PaymentInspectCommand.requested(new String[] {"--server.port=8080"})).isFalse();
    }

    @Test
    void disabledByDefaultButInspectionDoesNotDependOnPaymentCreditOrAiFlags() {
        assertFailure(arguments(), Map.of(), "OPERATOR_COMMAND_DISABLED");
        var env = environment();
        for (String key :
                List.of("MONGLE_PAYMENT_ENABLED", "MONGLE_PAYAPP_ENABLED", "MONGLE_CREDIT_ENABLED"))
            env.put(key, "false");
        env.put("SPRING_PROFILES_ACTIVE", "not-a-profile");
        env.put("SERVER_PORT", "not-a-port");
        assertThat(PaymentInspectSettings.fromEnvironment(env).toString())
                .isEqualTo("PaymentInspectSettings[redacted]");
    }

    @Test
    void rejectsWrongIdentityDatabaseHostAndUnsafeJdbcOptionsBeforeConnecting() {
        for (String url :
                List.of(
                        "jdbc:h2:mem:test",
                        "jdbc:mysql://other.example.invalid/mongle",
                        "jdbc:mysql://db.example.invalid/other",
                        "jdbc:mysql://db.example.invalid/mongle?sslMode=DISABLED",
                        "jdbc:mysql://secret:password@db.example.invalid/mongle",
                        "jdbc:mysql://db.example.invalid/mongle#private")) {
            var env = environment();
            env.put("DB_URL", url);
            assertFailure(arguments(), env, "INVALID_CONFIGURATION");
        }
        for (String user : List.of("root", "mongle_admin", "mongle_migrator", "mongle_test")) {
            var env = environment();
            env.put("DB_USERNAME", user);
            assertFailure(arguments(), env, "INVALID_CONFIGURATION");
        }
        for (String path :
                List.of(
                        "file:relative",
                        "file://other/etc/private",
                        "https://example.invalid/trust.p12")) {
            var env = environment();
            env.put("DB_TRUSTSTORE_URL", path);
            assertFailure(arguments(), env, "INVALID_CONFIGURATION");
        }
        for (String key :
                List.of(
                        "DB_PASSWORD",
                        "DB_TRUSTSTORE_PASSWORD",
                        "DB_TRUSTSTORE_URL",
                        "MONGLE_PAYMENT_INSPECT_EXPECTED_HOST",
                        "MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE")) {
            var env = environment();
            env.remove(key);
            assertFailure(arguments(), env, "INVALID_CONFIGURATION");
        }
    }

    @Test
    void settingsForceVerifiedTlsUtcAndServerSideReadOnlyPropagation() {
        var source =
                (DriverManagerDataSource)
                        PaymentInspectSettings.fromEnvironment(environment()).dataSource();
        assertThat(source.getConnectionProperties())
                .containsEntry("sslMode", "VERIFY_IDENTITY")
                .containsEntry("fallbackToSystemTrustStore", "false")
                .containsEntry("trustCertificateKeyStoreType", "PKCS12")
                .containsEntry("readOnlyPropagatesToServer", "true")
                .containsEntry("connectionTimeZone", "UTC")
                .containsEntry("connectTimeout", "5000")
                .containsEntry("socketTimeout", "5000");
    }

    @Test
    void connectionFailureIsOneSanitizedJsonLineInsteadOfBootStartupOrRawJdbcErrors() {
        var env = environment();
        env.put("DB_URL", "jdbc:mysql://127.0.0.1:1/mongle");
        env.put("MONGLE_PAYMENT_INSPECT_EXPECTED_HOST", "127.0.0.1");
        assertFailure(arguments(), env, "DB_CONNECTION_FAILED");
    }

    static void assertFailure(String[] args, Map<String, String> environment, String code) {
        var output = new ByteArrayOutputStream();
        assertThat(PaymentInspectCommand.run(args, environment, new PrintStream(output)))
                .isEqualTo(1);
        String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(text.lines().count()).isOne();
        assertThat(text)
                .doesNotContain(
                        "private-db-password",
                        "private-trust-password",
                        "jdbc:",
                        "db.example.invalid",
                        "Exception",
                        "StackTrace");
        var json = JsonMapper.builder().build().readTree(text);
        assertThat(json.path("event").asString()).isEqualTo("payment-inspection-failed");
        assertThat(json.path("code").asString()).isEqualTo(code);
    }
}
