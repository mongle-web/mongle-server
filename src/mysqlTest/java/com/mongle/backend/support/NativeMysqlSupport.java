package com.mongle.backend.support;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/** Configuration supplied only by the disposable native-MySQL runner. */
public final class NativeMysqlSupport {
    private NativeMysqlSupport() {}

    public static void register(DynamicPropertyRegistry registry, String database) {
        String port = required("MONGLE_MYSQL_TEST_PORT");
        int value = Integer.parseInt(port);
        if (value < 1
                || value > 65535
                || !List.of("credit_test", "payment_test").contains(database)) {
            throw new IllegalStateException("Invalid disposable MySQL fixture");
        }
        registry.add(
                "spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:" + value + "/" + database);
        registry.add("spring.datasource.username", () -> "mongle_test");
        registry.add("spring.datasource.password", () -> required("MONGLE_MYSQL_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 8);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        registry.add("mongle.auth.secret", () -> Base64.getEncoder().encodeToString(secret));
        registry.add("mongle.auth.cookie-secure", () -> true);
        for (String key :
                List.of(
                        "mongle.dream.generation.enabled",
                        "mongle.image.recovery.enabled",
                        "mongle.image.cleanup.enabled",
                        "mongle.image.openai.enabled",
                        "mongle.image.s3.enabled",
                        "mongle.world.composition.worker-enabled")) {
            registry.add(key, () -> false);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Run python3 scripts/mysql84-test.py to create an isolated fixture");
        }
        return value;
    }
}
