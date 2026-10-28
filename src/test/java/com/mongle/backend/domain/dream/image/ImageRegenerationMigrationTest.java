package com.mongle.backend.domain.dream.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ImageRegenerationMigrationTest {
    @Test void v2PreservesV1ResultsAndGrantsExistingImagesOneFreeRegeneration() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:image-policy-"+UUID.randomUUID()+";MODE=MySQL");
                var sql=connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V1__mongle_baseline.sql"));
            sql.execute("insert into users(id,email,nickname,created_at,updated_at) values(1,'policy@test.invalid','policy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.execute("insert into dream_analyses(id,user_id,source_dream_id,dreamed_at,source_revision,observed_revision,prompt_version,status,attempt_id,created_at,updated_at) values(2,1,99,CURRENT_DATE,1,1,'test','COMPLETED','old',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.execute("insert into dream_images(id,user_id,analysis_id,source_revision,story_hash,style,status,attempt_id,storage_key,created_at,updated_at) values(3,1,2,1,'hash','old-style','COMPLETED','old','dream-images/old.png',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.execute("insert into world_folders(id,user_id,name,created_at,updated_at) values(4,1,'world',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.execute("insert into world_images(id,user_id,world_id,composition_revision,style,status,attempt_id,storage_key,created_at,updated_at) values(5,1,4,1,'old-style','COMPLETED','old','world-images/old.png',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V2__image_regeneration_policy.sql"));
            for(var table:java.util.List.of("dream_images","world_images")) {
                try(var rows=sql.executeQuery("select free_regeneration_used,free_regeneration_attempt,storage_key,style,status from "+table)) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getBoolean(1)).isFalse();assertThat(rows.getBoolean(2)).isFalse();
                    assertThat(rows.getString(3)).endsWith("/old.png");assertThat(rows.getString(4)).isEqualTo("old-style");assertThat(rows.getString(5)).isEqualTo("COMPLETED");
                }
            }
        }
    }
}
