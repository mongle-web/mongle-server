package com.mongle.backend.migration;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MigrationCommandTest {
    static Map<String, String> environment() {
        var values = new HashMap<String, String>();
        values.put("MONGLE_MIGRATION_DB_URL", "jdbc:mysql://example.rds.amazonaws.com:3306/mongle");
        values.put("MONGLE_MIGRATION_EXPECTED_HOST", "example.rds.amazonaws.com");
        values.put("MONGLE_MIGRATION_EXPECTED_DATABASE", "mongle");
        values.put("MONGLE_MIGRATION_DB_USERNAME", "mongle_migrator");
        values.put("MONGLE_MIGRATION_DB_PASSWORD", "private-database-password");
        values.put("MONGLE_MIGRATION_TRUSTSTORE_URL", "file:/etc/mongle/migration/rds-truststore.p12");
        values.put("MONGLE_MIGRATION_TRUSTSTORE_PASSWORD", "private-store-password");
        return values;
    }

    @Test void applicationArgumentsDoNotEnterMigrationModeButMalformedMigrationArgumentsDo() {
        assertThat(MigrationCommand.requested(new String[] {"--spring.profiles.active=mysql"})).isFalse();
        assertThat(MigrationCommand.requested(new String[] {"--db-migration=migrate"})).isTrue();
        assertThat(MigrationCommand.requested(new String[] {"--db-migration-clean"})).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"clean", "repair", "undo", "baselineOnMigrate", "", "migrate;id"})
    void unsupportedOperationsAreRejectedBeforeConnecting(String operation) {
        var bytes = new ByteArrayOutputStream();
        assertThat(MigrationCommand.run(new String[] {"--db-migration=" + operation}, Map.of(),
                new PrintStream(bytes))).isEqualTo(1);
        assertThat(bytes.toString()).contains("UNSUPPORTED_OPERATION");
    }

    @Test void extraArgumentsAndApplicationCredentialsCannotBeUsedAsMigrationCredentials() {
        var bytes = new ByteArrayOutputStream();
        assertThat(MigrationCommand.run(new String[] {"--db-migration=migrate", "--password=private"},
                Map.of(), new PrintStream(bytes))).isEqualTo(1);
        assertThat(bytes.toString()).contains("INVALID_ARGUMENTS").doesNotContain("private");
        bytes.reset();
        assertThat(MigrationCommand.run(new String[] {"--db-migration=migrate"},
                Map.of("DB_URL", "private-url", "DB_PASSWORD", "private-password"),
                new PrintStream(bytes))).isEqualTo(1);
        assertThat(bytes.toString()).contains("INVALID_CONFIGURATION").doesNotContain("private");
    }

    @Test void dedicatedTlsSettingsAreRequiredWithoutExposingSecrets() {
        var settings = MigrationSettings.fromEnvironment(environment());
        assertThat(settings.database()).isEqualTo("mongle");
        assertThat(settings.toString()).doesNotContain("private", "rds.amazonaws.com");
        for (String key : environment().keySet()) {
            var values = environment(); values.remove(key);
            assertThatThrownBy(() -> MigrationSettings.fromEnvironment(values))
                    .isInstanceOf(MigrationFailure.class).hasMessage("INVALID_CONFIGURATION");
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "jdbc:mysql://other.rds.amazonaws.com:3306/mongle",
            "jdbc:mysql://example.rds.amazonaws.com:3306/other",
            "jdbc:mysql://example.rds.amazonaws.com:3306/mongle?sslMode=DISABLED",
            "jdbc:mysql://private:password@example.rds.amazonaws.com/mongle",
            "jdbc:mysql://example.rds.amazonaws.com/mongle#private", "invalid"})
    void refusesUnexpectedTargetsAndJdbcOverrides(String url) {
        var values = environment(); values.put("MONGLE_MIGRATION_DB_URL", url);
        assertThatThrownBy(() -> MigrationSettings.fromEnvironment(values)).hasMessage("INVALID_CONFIGURATION");
    }

    @Test void applicationAdminAccountsAndRemoteTruststoresAreRefused() {
        for (String user : new String[] {"mongle_app", "mongle_admin", "root"}) {
            var values = environment(); values.put("MONGLE_MIGRATION_DB_USERNAME", user);
            assertThatThrownBy(() -> MigrationSettings.fromEnvironment(values)).hasMessage("INVALID_CONFIGURATION");
        }
        var values = environment(); values.put("MONGLE_MIGRATION_TRUSTSTORE_URL", "https://example.com/store.p12");
        assertThatThrownBy(() -> MigrationSettings.fromEnvironment(values)).hasMessage("INVALID_CONFIGURATION");
    }
}
