package com.mongle.backend.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mongle-schema;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema-initial.sql",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
class InitialSchemaValidationTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void validatesJpaMappingsAgainstErdBasedSqlInsteadOfGeneratedTables() {
        assertThat(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'dream_scene_entities'
                """, String.class)).containsExactlyInAnyOrder("dream_scene_id", "dream_entity_id");
        assertThat(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'ai_generation_logs'
                """, String.class)).contains("created_at").doesNotContain("updated_at");

        assertThat(jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema = 'public' and table_name = 'users' and column_name = 'nickname'
                """, String.class)).isEqualTo("YES");
        assertThat(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'social_accounts'
                """, String.class)).contains("user_id", "provider", "provider_user_id", "created_at", "updated_at");
        assertThat(jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'refresh_sessions'
                """, String.class)).containsExactlyInAnyOrder("id", "user_id", "token_hash", "expires_at", "created_at");
    }
}
