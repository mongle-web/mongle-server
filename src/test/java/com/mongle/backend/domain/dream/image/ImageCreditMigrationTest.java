package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import java.util.regex.Pattern;

/** MySQL 프로시저 실행이 아닌 실제 DDL 정의의 H2 MySQL 모드 검증이다. */
class ImageCreditMigrationTest {
    @Test void nullableReservationLinkPreservesLegacyResultsAndRerunKeepsNewLinks() throws Exception {
        var sql=new ClassPathResource("db/migrations/20261026-image-credit-charging.sql").getContentAsString(StandardCharsets.UTF_8);
        var column=Pattern.compile("ALTER TABLE dream_images ADD COLUMN ([^;]+);").matcher(sql);
        var index=Pattern.compile("CREATE INDEX ([^;]+);").matcher(sql);
        assertThat(column.find()).isTrue(); assertThat(index.find()).isTrue();
        assertThat(sql).contains("INFORMATION_SCHEMA.COLUMNS","INFORMATION_SCHEMA.STATISTICS");
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:image-credit-migration-"+UUID.randomUUID()+";MODE=MySQL");var statement=connection.createStatement()) {
            statement.execute("create table dream_images(id BIGINT PRIMARY KEY,status VARCHAR(30),lease_until DATETIME(6),storage_key VARCHAR(255))");
            statement.execute("insert into dream_images values(1,'COMPLETED',null,'legacy-success')");
            for(int run=0;run<2;run++) {
                statement.execute("alter table dream_images add column if not exists "+column.group(1));
                statement.execute("create index if not exists "+index.group(1));
                try(var rows=statement.executeQuery("select storage_key,credit_reservation_id from dream_images where id=1")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("legacy-success");
                    assertThat(rows.getObject(2)).isEqualTo(run==0?null:42L);
                }
                statement.execute("update dream_images set credit_reservation_id=42 where id=1");
            }
        }
    }
}
