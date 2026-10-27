package com.mongle.backend.domain.world.image;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.*;
import java.util.UUID;

class WorldImageMigrationTest {
    @Test void repeatableMigrationPreservesSuccessfulImageAndBothSourceLanes() throws Exception {
        try(var c=DriverManager.getConnection("jdbc:h2:mem:world-image-migration-"+UUID.randomUUID()+";MODE=MySQL");var s=c.createStatement()) {
            s.execute("create table users(id BIGINT PRIMARY KEY)");s.execute("create table world_folders(id BIGINT PRIMARY KEY)");
            s.execute("insert into users values(1)");s.execute("insert into world_folders values(2)");
            var sql=new ClassPathResource("db/migrations/20261026-world-images.sql");ScriptUtils.executeSqlScript(c,sql);
            s.execute("insert into world_images(id,user_id,world_id,composition_revision,style,status,attempt_id,storage_key,result_composition_revision,created_at,updated_at)"
                    + " values(3,1,2,8,'watercolor','PROCESSING','attempt','world-images/private',7,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            s.execute("insert into world_image_sources(image_id,user_id,dream_id,story_version_id,source_revision,candidate) values(3,1,4,5,1,false),(3,1,4,6,2,true)");
            ScriptUtils.executeSqlScript(c,sql);
            try(var r=s.executeQuery("select result_composition_revision,storage_key from world_images")) {
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(7);assertThat(r.getString(2)).isEqualTo("world-images/private");
            }
            try(var r=s.executeQuery("select story_version_id from world_image_sources order by candidate")) {
                assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(5);assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(6);
            }
            assertThatThrownBy(()->s.execute("delete from world_images where id=3")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.execute("delete from world_folders where id=2")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.execute("insert into world_image_sources(image_id,user_id,dream_id,story_version_id,source_revision,candidate) values(3,1,4,7,2,true)"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
