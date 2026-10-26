package com.mongle.backend.domain.world.composition;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.*;
import java.util.UUID;

class WorldCompositionMigrationTest {
    @Test
    void repeatableMigrationKeepsCompletedAndCandidateReferencesSeparate() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                "jdbc:h2:mem:world-migration-"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL");
                var s = c.createStatement()) {
            s.execute("create table users(id BIGINT PRIMARY KEY)");
            s.execute("create table world_folders(id BIGINT PRIMARY KEY)");
            s.execute("insert into users values(1)");
            s.execute("insert into world_folders values(2)");
            var sql = new ClassPathResource("db/migrations/20261026-world-composition.sql");
            ScriptUtils.executeSqlScript(c, sql);
            s.execute(
                    "insert into world_compositions(world_id,user_id,status,created_at,updated_at)"
                            + " values(2,1,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            s.execute(
                    "insert into"
                        + " world_composition_entries(world_id,user_id,candidate,position,dream_id,story_version_id,needs_bridge,created_at)"
                        + " values(2,1,false,0,3,4,false,CURRENT_TIMESTAMP),(2,1,true,0,3,5,false,CURRENT_TIMESTAMP)");
            ScriptUtils.executeSqlScript(c, sql);
            try (var rows =
                    s.executeQuery(
                            "select story_version_id from world_composition_entries order by"
                                    + " candidate")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(4);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(5);
            }
            assertThatThrownBy(() -> s.execute("delete from world_folders where id=2"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                            () ->
                                    s.execute(
                                            "insert into"
                                                + " world_composition_entries(world_id,user_id,candidate,position,dream_id,story_version_id,needs_bridge,created_at)"
                                                + " values(2,1,false,0,3,4,false,CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
