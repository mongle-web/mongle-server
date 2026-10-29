package com.mongle.backend.operations.credit;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

class TestCreditCommandTest {
    static Map<String, String> environment() {
        return new HashMap<>(
                Map.of(
                        "MONGLE_TEST_CREDIT_ENABLED",
                        "true",
                        "MONGLE_CREDIT_ENABLED",
                        "true",
                        "MONGLE_TEST_CREDIT_EXPECTED_HOST",
                        "db.example.invalid",
                        "MONGLE_TEST_CREDIT_EXPECTED_DATABASE",
                        "mongle",
                        "DB_URL",
                        "jdbc:mysql://db.example.invalid:3306/mongle",
                        "DB_USERNAME",
                        "mongle_app",
                        "DB_PASSWORD",
                        "never-print-password",
                        "DB_TRUSTSTORE_URL",
                        "file:/etc/mongle/rds-truststore.p12",
                        "DB_TRUSTSTORE_PASSWORD",
                        "never-print-trust-secret"));
    }

    static String[] arguments() {
        return new String[] {
            "--test-credit=preview", "--user-id=1", "--amount=100", "--request-key=qa-20261029-1"
        };
    }

    @Test
    void acceptsOnlyExplicitPreviewOrGrantWithFourNamedArguments() {
        assertThat(TestCreditRequest.parse(arguments()))
                .isEqualTo(new TestCreditRequest("preview", 1L, 100, "qa-20261029-1"));
        var grant = arguments();
        grant[0] = "--test-credit=grant";
        assertThat(TestCreditRequest.parse(grant).operation()).isEqualTo("grant");
        var shuffled = List.of(grant[3], grant[0], grant[2], grant[1]);
        assertThat(TestCreditRequest.parse(shuffled.toArray(String[]::new)))
                .isEqualTo(TestCreditRequest.parse(grant));
    }

    @Test
    void rejectsUnknownDuplicateMissingArgumentsAndUnboundedInvalidInputWithoutConnecting() {
        var invalid = new ArrayList<String[]>();
        invalid.add(new String[] {"--test-credit=grant"});
        invalid.add(
                new String[] {
                    "--test-credit=grant", "--user-id=1", "--amount=100", "--amount=200"
                });
        invalid.add(
                new String[] {
                    "--test-credit=grant", "--user-id=1", "--amount=100", "--db-migration=migrate"
                });
        for (String operation : List.of("", "help", "refund", "grant-more")) {
            var args = arguments();
            args[0] = "--test-credit=" + operation;
            invalid.add(args);
        }
        for (String id : List.of("0", "-1", "abc", "9223372036854775808")) {
            var args = arguments();
            args[1] = "--user-id=" + id;
            invalid.add(args);
        }
        for (String amount : List.of("0", "-1", "1.5", "9223372036854775808")) {
            var args = arguments();
            args[2] = "--amount=" + amount;
            invalid.add(args);
        }
        for (String key : List.of("", "a b", "a\nsecret", "-prefix", "x".repeat(117))) {
            var args = arguments();
            args[3] = "--request-key=" + key;
            invalid.add(args);
        }
        for (var args : invalid) assertFailure(args, environment(), "INVALID_ARGUMENTS");
    }

    @Test
    void disabledByDefaultAndCreditDisabledEvenWhenOperatorFlagIsEnabled() {
        assertFailure(arguments(), Map.of(), "OPERATOR_COMMAND_DISABLED");
        var env = environment();
        env.put("MONGLE_TEST_CREDIT_ENABLED", "false");
        assertFailure(arguments(), env, "OPERATOR_COMMAND_DISABLED");
        env.put("MONGLE_TEST_CREDIT_ENABLED", "true");
        env.put("MONGLE_CREDIT_ENABLED", "false");
        assertFailure(arguments(), env, "CREDIT_503_1");
    }

    @Test
    void bindsExpectedTargetAndAppDmlIdentityWithoutAllowingJdbcOverrides() {
        for (String url :
                List.of(
                        "jdbc:h2:mem:wrong",
                        "jdbc:mysql://other.invalid/mongle",
                        "jdbc:mysql://db.example.invalid/other",
                        "jdbc:mysql://db.example.invalid/mongle?sslMode=DISABLED",
                        "jdbc:mysql://user:secret@db.example.invalid/mongle",
                        "jdbc:mysql://db.example.invalid/mongle#fragment")) {
            var env = environment();
            env.put("DB_URL", url);
            assertFailure(arguments(), env, "INVALID_CONFIGURATION");
        }
        for (String user : List.of("root", "mongle_admin", "mongle_migrator", "")) {
            var env = environment();
            env.put("DB_USERNAME", user);
            assertFailure(arguments(), env, "INVALID_CONFIGURATION");
        }
        for (String trust :
                List.of(
                        "https://example.invalid/trust",
                        "file://remote/etc/trust",
                        "file:relative")) {
            var env = environment();
            env.put("DB_TRUSTSTORE_URL", trust);
            assertFailure(arguments(), env, "INVALID_CONFIGURATION");
        }
        for (String key :
                List.of(
                        "DB_PASSWORD",
                        "DB_TRUSTSTORE_PASSWORD",
                        "MONGLE_TEST_CREDIT_EXPECTED_HOST",
                        "MONGLE_TEST_CREDIT_EXPECTED_DATABASE")) {
            var env = environment();
            env.remove(key);
            assertFailure(arguments(), env, "INVALID_CONFIGURATION");
        }
        var env = environment();
        env.put("MONGLE_CREDIT_EXPIRY_POLICY", "UNDECIDED");
        assertFailure(arguments(), env, "INVALID_CONFIGURATION");
    }

    @Test
    void settingsEnforceVerifiedTlsTimeoutsAndRedactTheirRepresentation() {
        var settings = TestCreditSettings.fromEnvironment(environment());
        var source = (DriverManagerDataSource) settings.dataSource();
        assertThat(source.getConnectionProperties())
                .containsEntry("sslMode", "VERIFY_IDENTITY")
                .containsEntry("fallbackToSystemTrustStore", "false")
                .containsEntry("connectTimeout", "5000")
                .containsEntry("socketTimeout", "15000")
                .containsEntry("trustCertificateKeyStoreType", "PKCS12");
        assertThat(settings.toString()).isEqualTo("TestCreditSettings[redacted]");
        assertThat(settings.creditProperties().enabled()).isTrue();
    }

    @Test
    void malformedCommandMarkerStillBypassesNormalServerStartup() {
        assertThat(TestCreditCommand.requested(new String[] {"--test-credit-typo=grant"})).isTrue();
        assertThat(TestCreditCommand.requested(new String[] {"--server.port=8080"})).isFalse();
    }

    static void assertFailure(String[] arguments, Map<String, String> environment, String code) {
        var bytes = new ByteArrayOutputStream();
        assertThat(TestCreditCommand.run(arguments, environment, new PrintStream(bytes)))
                .isEqualTo(1);
        var body =
                JsonMapper.builder()
                        .build()
                        .readTree(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.get("event").asString()).isEqualTo("test-credit-failed");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.size()).isEqualTo(2);
        assertThat(bytes.toString())
                .doesNotContain("never-print", "jdbc:", "request-key", "Exception", "password");
    }
}
