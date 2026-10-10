package com.mongle.backend.domain.dream.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.regex.Pattern;

/** H2에서 컬럼 정의·재실행·보수적 백필을 검증한다. MySQL 프로시저 실행 검증은 아니다. */
class DreamGenerationMigrationTest {
    @Test
    void columnDefinitionsAllowRerunWithoutGuessingLegacyInputOrSettings() throws Exception {
        String sql =
                new ClassPathResource("db/migrations/20261010-dream-generation-versioning.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        var columns =
                Pattern.compile(
                        "CALL mongle_add_generation_column\\('([^']+)','([^']+)','([^']+)'\\);");
        try (var connection =
                DriverManager.getConnection(
                        "jdbc:h2:mem:versioning-" + UUID.randomUUID() + ";MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE dream_analyses(id BIGINT, observed_revision BIGINT, status"
                            + " VARCHAR(30))");
                statement.execute("CREATE TABLE dream_stories(id BIGINT)");
                statement.execute("CREATE TABLE dream_generation_jobs(id BIGINT)");
                statement.execute("CREATE TABLE dream_story_versions(id BIGINT)");
                statement.execute(
                        "INSERT INTO dream_analyses VALUES (1,7,'COMPLETED'),(2,9,'FAILED')");
                for (int rerun = 0; rerun < 2; rerun++) {
                    var calls = columns.matcher(sql);
                    int count = 0;
                    while (calls.find()) {
                        statement.execute(
                                "ALTER TABLE "
                                        + calls.group(1)
                                        + " ADD COLUMN IF NOT EXISTS "
                                        + calls.group(2)
                                        + " "
                                        + calls.group(3));
                        count++;
                    }
                    assertThat(count).isEqualTo(14);
                    var backfill =
                            Pattern.compile("UPDATE dream_analyses[^;]+;", Pattern.DOTALL)
                                    .matcher(sql);
                    assertThat(backfill.find()).isTrue();
                    statement.execute(backfill.group());
                }
                try (var rows =
                        statement.executeQuery(
                                "SELECT"
                                    + " result_revision,source_text,source_emotions,generation_settings,regenerating"
                                    + " FROM dream_analyses ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(7);
                    assertThat(rows.getString(2)).isNull();
                    assertThat(rows.getString(3)).isNull();
                    assertThat(rows.getString(4)).isNull();
                    assertThat(rows.getBoolean(5)).isFalse();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1)).isNull();
                }
            }
        }
    }
}
