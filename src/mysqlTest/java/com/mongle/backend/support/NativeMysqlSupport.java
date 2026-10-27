package com.mongle.backend.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import com.mongle.backend.migration.MigrationSettings;
import com.mongle.backend.migration.MigrationEngine;
import java.util.Map;

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
                || !List.of("credit_test", "payment_test", "flyway_jpa").contains(database)) {
            throw new IllegalStateException("Invalid disposable MySQL fixture");
        }
        if (!"flyway_jpa".equals(database)) {
            // Only the loopback disposable fixtures enter this path; never a production baseline.
            var settings = MigrationSettings.fromEnvironment(Map.of(
                    "MONGLE_MIGRATION_DB_URL", "jdbc:mysql://127.0.0.1:" + value + "/" + database,
                    "MONGLE_MIGRATION_EXPECTED_HOST", "127.0.0.1",
                    "MONGLE_MIGRATION_EXPECTED_DATABASE", database,
                    "MONGLE_MIGRATION_DB_USERNAME", "mongle_migrator",
                    "MONGLE_MIGRATION_DB_PASSWORD", required("MONGLE_MYSQL_MIGRATOR_PASSWORD"),
                    "MONGLE_MIGRATION_TRUSTSTORE_URL", required("DB_TRUSTSTORE_URL"),
                    "MONGLE_MIGRATION_TRUSTSTORE_PASSWORD", required("DB_TRUSTSTORE_PASSWORD")));
            var engine = new MigrationEngine(settings.dataSource(), database);
            try {
                if (Boolean.FALSE.equals(engine.execute("info").get("historyExists"))) engine.execute("baseline");
                engine.execute("migrate");
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException("Disposable fixture migration failed");
            }
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
