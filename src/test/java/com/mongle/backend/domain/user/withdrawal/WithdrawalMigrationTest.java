package com.mongle.backend.domain.user.withdrawal;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class WithdrawalMigrationTest {
    @Test void expandsExistingUsersAndCreditsWithoutChangingBusinessData() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:withdrawal-expand;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-initial.sql")).execute(source);
        var jdbc=new JdbcTemplate(source);
        jdbc.update("insert into users(email,nickname,created_at,updated_at) values('keep@example.test','기존회원',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        Long user=jdbc.queryForObject("select id from users where email='keep@example.test'",Long.class);
        jdbc.update("insert into credit_grants(user_id,source_type,source_key,source_hash,amount,available,reserved,consumed,version,created_at)"
                +" values(?,'SIGNUP','signup:existing',REPEAT('a',64),3,3,0,0,0,CURRENT_TIMESTAMP)",user);
        var before=jdbc.queryForMap("select * from credit_grants where user_id=?",user);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V7__user_withdrawal.sql")).execute(source);
        var after=jdbc.queryForMap("select * from credit_grants where user_id=?",user);
        assertThat(after.remove("forfeited")).isEqualTo(0L);
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("select nickname from users where id=?",String.class,user)).isEqualTo("기존회원");
        assertThat(jdbc.queryForMap("select withdrawn_at,retention_until from users where id=?",user).values()).containsOnlyNulls();
        assertThat(jdbc.queryForObject("select count(*) from signup_reward_history",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from social_unlink_tasks",Long.class)).isZero();
    }
}
