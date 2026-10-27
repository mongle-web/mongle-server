package com.mongle.backend.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** No component scanning, HTTP server, JPA, OAuth or business workers are started. */
public final class MigrationEngine {
    private final DataSource source;
    private final String database;
    private final Flyway flyway;

    public MigrationEngine(DataSource source, String database) {
        this(source, database, "classpath:db/migration");
    }

    MigrationEngine(DataSource source, String database, String... locations) {
        this.source = source;
        this.database = database;
        flyway = Flyway.configure().dataSource(source).locations(locations)
                .defaultSchema(database).schemas(database).createSchemas(false)
                .baselineVersion("1").baselineDescription("Mongle verified 33-table baseline")
                .baselineOnMigrate(false).cleanDisabled(true).outOfOrder(false)
                .validateOnMigrate(true).validateMigrationNaming(true).failOnMissingLocations(true)
                .ignoreMigrationPatterns(new String[0]).placeholderReplacement(false)
                .connectRetries(0).lockRetryCount(15).loggers(new String[0])
                .initSql("SET SESSION lock_wait_timeout=15").load();
    }

    public Map<String, Object> execute(String operation) throws SQLException {
        try (var connection = source.getConnection()) {
            verifyTarget(connection);
            var snapshot = SchemaSnapshot.capture(connection);
            boolean matches = snapshot.equals(SchemaSnapshot.baseline());
            boolean history = historyExists(connection);
            if ("info".equals(operation)) {
                return Map.of("businessTables", snapshot.path("tables").size(), "historyExists", history,
                        "matchesBaseline", matches, "schemaSha256", SchemaSnapshot.fingerprint(snapshot));
            }
            if ("baseline".equals(operation)) {
                if (history) throw new MigrationFailure("HISTORY_ALREADY_EXISTS");
                if (!matches) throw new MigrationFailure("BASELINE_SCHEMA_MISMATCH");
                flyway.baseline();
                return Map.of("baselineVersion", "1");
            }
            if (!"migrate".equals(operation)) throw new MigrationFailure("UNSUPPORTED_OPERATION");
            if (!history && !snapshot.path("tables").isEmpty()) {
                throw new MigrationFailure("EXPLICIT_BASELINE_REQUIRED");
            }
            var result = flyway.migrate();
            flyway.validate();
            return Map.of("migrationsExecuted", result.migrationsExecuted,
                    "targetVersion", result.targetSchemaVersion == null ? "none" : result.targetSchemaVersion);
        }
    }

    private void verifyTarget(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        if (!"MySQL".equals(metadata.getDatabaseProductName())
                || metadata.getDatabaseMajorVersion() != 8 || metadata.getDatabaseMinorVersion() != 4
                || !database.equals(connection.getCatalog())) throw new MigrationFailure("DATABASE_TARGET_MISMATCH");
    }

    private boolean historyExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=? AND table_name='flyway_schema_history'")) {
            statement.setString(1, database);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getInt(1) != 0; }
        }
    }
}
