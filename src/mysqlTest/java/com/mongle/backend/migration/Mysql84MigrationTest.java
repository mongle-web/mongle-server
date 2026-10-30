package com.mongle.backend.migration;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;

class Mysql84MigrationTest {
    @TempDir Path directory;

    static Map<String, String> environment(String database) {
        var values = new HashMap<String, String>();
        values.put("MONGLE_MIGRATION_DB_URL", "jdbc:mysql://127.0.0.1:" + System.getenv("MONGLE_MYSQL_TEST_PORT") + "/" + database);
        values.put("MONGLE_MIGRATION_EXPECTED_HOST", "127.0.0.1");
        values.put("MONGLE_MIGRATION_EXPECTED_DATABASE", database);
        values.put("MONGLE_MIGRATION_DB_USERNAME", "mongle_migrator");
        values.put("MONGLE_MIGRATION_DB_PASSWORD", System.getenv("MONGLE_MYSQL_MIGRATOR_PASSWORD"));
        values.put("MONGLE_MIGRATION_TRUSTSTORE_URL", System.getenv("DB_TRUSTSTORE_URL"));
        values.put("MONGLE_MIGRATION_TRUSTSTORE_PASSWORD", System.getenv("DB_TRUSTSTORE_PASSWORD"));
        return values;
    }

    static DataSource source(String database) {
        return MigrationSettings.fromEnvironment(environment(database)).dataSource();
    }

    String location() throws Exception {
        return "filesystem:" + directory;
    }

    @Test void immutableV1SqlAndBaselineCatalogDescribeExactlyTheSameSchema() throws Exception {
        try (var sql = new ClassPathResource("db/migration/V1__mongle_baseline.sql").getInputStream()) {
            Files.copy(sql, directory.resolve("V1__mongle_baseline.sql"));
        }
        var source = source("flyway_v1");
        new MigrationEngine(source, "flyway_v1", "filesystem:" + directory).execute("migrate");
        try (var connection = source.getConnection()) {
            assertThat(SchemaSnapshot.capture(connection)).isEqualTo(SchemaSnapshot.baseline());
        }
    }

    @Test void commandCreatesFreshSchemaOnceAndNeverBootstrapsBusinessWorkers() throws Exception {
        var output = new ByteArrayOutputStream();
        var env = environment("flyway_fresh");
        assertThat(MigrationCommand.run(new String[] {"--db-migration=migrate"}, env, new PrintStream(output))).isZero();
        assertThat(output.toString()).contains("migration-completed")
                .doesNotContain(env.get("MONGLE_MIGRATION_DB_PASSWORD"), env.get("MONGLE_MIGRATION_TRUSTSTORE_PASSWORD"), "Started Mongle");
        var jdbc = new JdbcTemplate(source("flyway_fresh"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isGreaterThanOrEqualTo(34);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=true", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='3' AND success=true", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name IN ('dream_images','world_images') AND column_name IN ('free_regeneration_used','free_regeneration_attempt') "
                + "AND is_nullable='NO' AND column_default='0'", Integer.class)).isEqualTo(4);
        output.reset();
        assertThat(MigrationCommand.run(new String[] {"--db-migration=migrate"}, env, new PrintStream(output))).isZero();
        assertThat(output.toString()).contains("migrationsExecuted\":0");
    }

    @Test void existingDatabaseRequiresExplicitBaselineAndPreservesItsData() throws Exception {
        var source = source("flyway_existing"); var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO users(email,nickname,created_at,updated_at) VALUES('preserved@test.invalid','keep',NOW(),NOW())");
        var engine = new MigrationEngine(source, "flyway_existing");
        assertThatThrownBy(() -> engine.execute("migrate")).hasMessage("EXPLICIT_BASELINE_REQUIRED");
        assertThat(engine.execute("info")).containsEntry("historyExists", false).containsEntry("matchesBaseline", true);
        assertThat(engine.execute("baseline")).containsEntry("baselineVersion", "1");
        // #86이 #85보다 먼저 배포된 실제 순서: V3까지만 적용된 DB에서 V4로 전진한다.
        var priorRelease = Files.createDirectory(directory.resolve("prior-image-release"));
        for (String name : java.util.List.of("V1__mongle_baseline.sql", "V3__image_regeneration_policy.sql")) {
            try (var sql = new ClassPathResource("db/migration/" + name).getInputStream()) {
                Files.copy(sql, priorRelease.resolve(name));
            }
        }
        assertThat(new MigrationEngine(source, "flyway_existing", "filesystem:" + priorRelease).execute("migrate"))
                .containsEntry("targetVersion", "3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='recent_interpretations'", Integer.class)).isZero();
        assertThat(engine.execute("migrate")).containsEntry("migrationsExecuted", 3).containsEntry("targetVersion", "6");
        assertThat(engine.execute("migrate")).containsEntry("migrationsExecuted", 0);
        assertThatThrownBy(() -> engine.execute("baseline")).hasMessage("HISTORY_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE email='preserved@test.invalid'", String.class)).isEqualTo("keep");
        assertThat(jdbc.queryForObject("SELECT type FROM flyway_schema_history WHERE version='1'", String.class)).isEqualTo("BASELINE");
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='4'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recent_interpretations", Integer.class)).isZero();
    }

    @Test void v5UpgradesExistingV4OnceWithoutChangingExistingDataOrMigrationChecksums() throws Exception {
        var prior=Files.createDirectory(directory.resolve("prior-summary-release"));
        for(String name:java.util.List.of("V1__mongle_baseline.sql","V3__image_regeneration_policy.sql","V4__recent_interpretations.sql")) {
            try(var sql=new ClassPathResource("db/migration/"+name).getInputStream()) { Files.copy(sql,prior.resolve(name)); }
        }
        var source=source("flyway_summary");var jdbc=new JdbcTemplate(source);
        assertThat(new MigrationEngine(source,"flyway_summary","filesystem:"+prior).execute("migrate")).containsEntry("targetVersion","4");
        jdbc.update("INSERT INTO users(email,nickname,created_at,updated_at) VALUES('summary-preserved@test.invalid','keep-summary',NOW(),NOW())");
        var history=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        var engine=new MigrationEngine(source,"flyway_summary");
        assertThat(engine.execute("migrate")).containsEntry("migrationsExecuted",2).containsEntry("targetVersion","6");
        assertThat(engine.execute("migrate")).containsEntry("migrationsExecuted",0);
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version in ('1','3','4') order by installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForObject("select nickname from users where email='summary-preserved@test.invalid'",String.class)).isEqualTo("keep-summary");
        assertThat(jdbc.queryForObject("select count(*) from world_summaries",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from world_summary_sources",Long.class)).isZero();
    }

    @Test void v6UpgradesV5OnceAndPreservesBusinessRowsAndAppliedHistory() throws Exception {
        var prior=Files.createDirectory(directory.resolve("prior-reclamation-release"));
        for(String name:java.util.List.of("V1__mongle_baseline.sql","V3__image_regeneration_policy.sql","V4__recent_interpretations.sql","V5__world_summaries.sql")) {
            try(var sql=new ClassPathResource("db/migration/"+name).getInputStream()) { Files.copy(sql,prior.resolve(name)); }
        }
        var ds=source("flyway_reclamation");var jdbc=new JdbcTemplate(ds);
        assertThat(new MigrationEngine(ds,"flyway_reclamation","filesystem:"+prior).execute("migrate")).containsEntry("targetVersion","5");
        jdbc.update("INSERT INTO users(email,nickname,created_at,updated_at) VALUES('reclamation-preserved@test.invalid','keep',NOW(),NOW())");
        Long user=jdbc.queryForObject("select id from users where email='reclamation-preserved@test.invalid'",Long.class);
        jdbc.update("insert into credit_accounts(user_id,available,reserved,version,created_at,updated_at) values(?,10,0,0,NOW(),NOW())",user);
        jdbc.update("insert into credit_grants(user_id,source_type,source_key,source_hash,amount,available,reserved,consumed,version,created_at) values(?,'PROMOTION','keep-source',REPEAT('a',64),10,10,0,0,0,NOW())",user);
        var history=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        var accounts=jdbc.queryForList("select * from credit_accounts");var grants=jdbc.queryForList("select * from credit_grants");
        var engine=new MigrationEngine(ds,"flyway_reclamation");
        assertThat(engine.execute("migrate")).containsEntry("migrationsExecuted",1).containsEntry("targetVersion","6");
        assertThat(engine.execute("migrate")).containsEntry("migrationsExecuted",0);
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version<>'6' order by installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForList("select * from credit_accounts")).isEqualTo(accounts);
        assertThat(jdbc.queryForList("select * from credit_grants")).isEqualTo(grants);
        assertThat(jdbc.queryForObject("select count(*) from payment_credit_reclamations",Long.class)).isZero();
    }

    @Test void baselineRejectsColumnIndexAndForeignKeyDriftEvenWith33Tables() throws Exception {
        var source = source("flyway_drift"); var jdbc = new JdbcTemplate(source);
        var engine = new MigrationEngine(source, "flyway_drift");
        jdbc.execute("ALTER TABLE users MODIFY nickname VARCHAR(101) NULL");
        assertThatThrownBy(() -> engine.execute("baseline")).hasMessage("BASELINE_SCHEMA_MISMATCH");
        jdbc.execute("ALTER TABLE users MODIFY nickname VARCHAR(100) NULL");
        jdbc.execute("CREATE INDEX drift_index ON users(nickname)");
        assertThatThrownBy(() -> engine.execute("baseline")).hasMessage("BASELINE_SCHEMA_MISMATCH");
        jdbc.execute("DROP INDEX drift_index ON users");
        jdbc.execute("ALTER TABLE social_accounts DROP FOREIGN KEY fk_social_accounts_user");
        assertThatThrownBy(() -> engine.execute("baseline")).hasMessage("BASELINE_SCHEMA_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isEqualTo(33);
        assertThat(engine.execute("info")).containsEntry("historyExists", false);
    }

    @Test void baselineRejectsMissingModifiedAndDisabledCheckConstraintsWithoutCreatingHistory() throws Exception {
        var source = source("flyway_check"); var jdbc = new JdbcTemplate(source);
        var engine = new MigrationEngine(source, "flyway_check");
        assertThat(engine.execute("info")).containsEntry("matchesBaseline", true);
        jdbc.execute("ALTER TABLE credit_accounts ALTER CHECK ck_credit_account_balance NOT ENFORCED");
        assertThatThrownBy(() -> engine.execute("baseline")).hasMessage("BASELINE_SCHEMA_MISMATCH");
        jdbc.execute("ALTER TABLE credit_accounts ALTER CHECK ck_credit_account_balance ENFORCED");
        assertThat(engine.execute("info")).containsEntry("matchesBaseline", true);
        jdbc.execute("ALTER TABLE credit_accounts DROP CHECK ck_credit_account_balance, "
                + "ADD CONSTRAINT ck_credit_account_balance CHECK (available >= -1 AND reserved >= 0)");
        assertThatThrownBy(() -> engine.execute("baseline")).hasMessage("BASELINE_SCHEMA_MISMATCH");
        jdbc.execute("ALTER TABLE credit_accounts DROP CHECK ck_credit_account_balance");
        assertThatThrownBy(() -> engine.execute("baseline")).hasMessage("BASELINE_SCHEMA_MISMATCH");
        assertThat(engine.execute("info")).containsEntry("businessTables", 33).containsEntry("historyExists", false);
    }

    @Test void checksumMismatchCannotBeRepairedOrReappliedAutomatically() throws Exception {
        String location = location();
        Path second = directory.resolve("V9000__additional_column.sql");
        Files.writeString(second, "ALTER TABLE users ADD COLUMN release_marker VARCHAR(20) NULL;");
        var source = source("flyway_checksum");
        var engine = new MigrationEngine(source, "flyway_checksum", "classpath:db/migration", location);
        assertThat(engine.execute("migrate")).containsKey("migrationsExecuted");
        Files.writeString(second, "ALTER TABLE users ADD COLUMN different_marker VARCHAR(20) NULL;");
        assertThatThrownBy(() -> new MigrationEngine(source, "flyway_checksum", "classpath:db/migration", location).execute("migrate"))
                .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='different_marker'", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT id,email,nickname FROM users")).isEmpty();
        // An older artifact cannot silently ignore a newer migration history.
        assertThatThrownBy(() -> new MigrationEngine(source, "flyway_checksum").execute("migrate"))
                .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
        // Reverting app code remains deployable when the already applied SQL is preserved exactly.
        Files.writeString(second, "ALTER TABLE users ADD COLUMN release_marker VARCHAR(20) NULL;");
        assertThat(new MigrationEngine(source, "flyway_checksum", "classpath:db/migration", location)
                .execute("migrate")).containsEntry("migrationsExecuted", 0);
    }

    @Test void failedMysqlDdlStaysVisibleAndIsNotCleanedOrMarkedSuccessful() throws Exception {
        String location = location();
        Files.writeString(directory.resolve("V9000__partial_failure.sql"),
                "CREATE TABLE partial_migration(id BIGINT PRIMARY KEY); INSERT INTO missing_migration_table VALUES(1);");
        var source = source("flyway_failed");
        assertThatThrownBy(() -> new MigrationEngine(source, "flyway_failed", "classpath:db/migration", location).execute("migrate"))
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='partial_migration'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='9000'", Boolean.class)).isFalse();
        assertThatThrownBy(() -> new MigrationEngine(source, "flyway_failed", "classpath:db/migration", location).execute("migrate"))
                .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
    }

    @Test void applicationDmlAccountCannotCreateHistoryAndWrongTargetsNeverMigrate() throws Exception {
        var settings = MigrationSettings.fromEnvironment(environment("flyway_privilege"));
        var app = new MigrationSettings(settings.url(), "mongle_test", System.getenv("MONGLE_MYSQL_TEST_PASSWORD"),
                settings.truststore(), settings.truststorePassword(), settings.database());
        assertThatThrownBy(() -> new MigrationEngine(app.dataSource(), app.database()).execute("baseline"))
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        assertThat(new MigrationEngine(settings.dataSource(), settings.database()).execute("info"))
                .containsEntry("historyExists", false);
        assertThatThrownBy(() -> new MigrationEngine(settings.dataSource(), "other_database").execute("baseline"))
                .hasMessage("DATABASE_TARGET_MISMATCH");
        var env = environment("flyway_privilege"); env.put("MONGLE_MIGRATION_TRUSTSTORE_URL", "file:/nonexistent-private-store.p12");
        var output = new ByteArrayOutputStream();
        assertThat(MigrationCommand.run(new String[] {"--db-migration=info"}, env, new PrintStream(output))).isEqualTo(1);
        assertThat(output.toString()).contains("MIGRATION_FAILED")
                .doesNotContain("private-store", env.get("MONGLE_MIGRATION_DB_PASSWORD"));
    }
}
