package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** MySQL 프로시저 자체 실행이 아닌 컬럼 정의·백필·인덱스의 H2 MySQL 모드 검증. */
class ImageCleanupMigrationTest {
    @Test
    void migratesLegacyRequestsAndRerunPreservesProcessingAndCompletedState() throws Exception {
        String sql = new ClassPathResource("db/migrations/20261018-image-cleanup-worker.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:cleanup-migration-"+UUID.randomUUID()+";MODE=MySQL")) {
            var statement = connection.createStatement();
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migrations/20261014-image-cleanup-requests.sql"));
            statement.execute("create table dream_images(id BIGINT,storage_key VARCHAR(255))");
            statement.execute("insert into image_cleanup_requests(user_id,dream_id,image_id,storage_key,reason,created_at) values(1,2,3,'private-key','DREAM_DELETED','2026-10-14 00:00:00')");
            apply(statement, sql);
            try (var rows = statement.executeQuery("select status,attempt_count,next_run_at,claim_token,deletion_started_at,requeue_requested,version from image_cleanup_requests")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("PENDING");
                assertThat(rows.getInt(2)).isZero();
                assertThat(rows.getTimestamp(3)).isEqualTo(Timestamp.valueOf("2026-10-14 00:00:00"));
                assertThat(rows.getString(4)).isNull();
                assertThat(rows.getTimestamp(5)).isNull();
                assertThat(rows.getBoolean(6)).isFalse();
                assertThat(rows.getLong(7)).isZero();
            }
            statement.execute("update image_cleanup_requests set status='PROCESSING',attempt_count=2,claim_token='claim',next_run_at=null");
            apply(statement, sql);
            try (var rows = statement.executeQuery("select status,attempt_count,claim_token,next_run_at from image_cleanup_requests")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("PROCESSING");
                assertThat(rows.getInt(2)).isEqualTo(2);
                assertThat(rows.getString(3)).isEqualTo("claim");
                assertThat(rows.getTimestamp(4)).isNull();
            }
            statement.execute("update image_cleanup_requests set status='COMPLETED',completed_at='2026-10-18 00:00:00'");
            apply(statement, sql);
            try (var rows = statement.executeQuery("select status,completed_at from image_cleanup_requests")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("COMPLETED");
                assertThat(rows.getTimestamp(2)).isEqualTo(Timestamp.valueOf("2026-10-18 00:00:00"));
            }
            statement.close();
        }
    }

    private void apply(Statement statement, String sql) throws Exception {
        var columns = Pattern.compile("CALL mongle_add_cleanup_column\\('([^']+)','((?:[^']|'')*)'\\);").matcher(sql);
        int count = 0;
        while (columns.find()) {
            statement.execute("alter table image_cleanup_requests add column if not exists "+columns.group(1)+" "+columns.group(2).replace("''", "'"));
            count++;
        }
        assertThat(count).isEqualTo(10);
        var backfill = Pattern.compile("UPDATE image_cleanup_requests[^;]+;").matcher(sql);
        assertThat(backfill.find()).isTrue();
        statement.execute(backfill.group());
        var indexes = Pattern.compile("CALL mongle_add_cleanup_index\\('([^']+)','([^']+)','([^']+)'\\);").matcher(sql);
        count = 0;
        while (indexes.find()) {
            statement.execute("create index if not exists "+indexes.group(2)+" on "+indexes.group(1)+"("+indexes.group(3)+")");
            count++;
        }
        assertThat(count).isEqualTo(3);
    }
}
