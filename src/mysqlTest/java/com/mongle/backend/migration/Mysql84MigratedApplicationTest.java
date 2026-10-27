package com.mongle.backend.migration;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.domain.user.entity.User;
import com.mongle.backend.domain.user.repository.UserRepository;
import com.mongle.backend.support.NativeMysqlSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("mysql")
class Mysql84MigratedApplicationTest {
    private static Path migrations;
    @Autowired UserRepository users;
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void migrated(DynamicPropertyRegistry registry) throws Exception {
        migrations = Files.createTempDirectory("mongle-migration-jpa-");
        try (var sql = new ClassPathResource("db/migration/V1__mongle_baseline.sql").getInputStream()) {
            Files.copy(sql, migrations.resolve("V1__mongle_baseline.sql"));
        }
        Files.writeString(migrations.resolve("V2__expand_users.sql"),
                "ALTER TABLE users ADD COLUMN future_release_marker VARCHAR(20) NULL;");
        new MigrationEngine(Mysql84MigrationTest.source("flyway_jpa"), "flyway_jpa", "filesystem:" + migrations)
                .execute("migrate");
        NativeMysqlSupport.register(registry, "flyway_jpa");
    }

    @AfterAll static void cleanup() throws Exception {
        if (migrations != null) {
            Files.deleteIfExists(migrations.resolve("V1__mongle_baseline.sql"));
            Files.deleteIfExists(migrations.resolve("V2__expand_users.sql"));
            Files.deleteIfExists(migrations);
        }
    }

    @Test void existingEntitiesValidateAndReadWriteAfterMigrationWithoutDdlPrivileges() {
        var jdbc = new JdbcTemplate(dataSource);
        var user = users.saveAndFlush(User.register(UUID.randomUUID() + "@test.invalid"));
        assertThat(users.findById(user.getId())).isPresent();
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT future_release_marker FROM users WHERE id=?", String.class, user.getId())).isNull();
        assertThatThrownBy(() -> jdbc.execute("ALTER TABLE users ADD COLUMN forbidden_column INT"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
