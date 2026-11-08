package com.mongle.backend.domain.payment;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payment-schema;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db/schema-initial.sql,classpath:db/migration/V3__image_regeneration_policy.sql,classpath:db/migration/V4__recent_interpretations.sql,classpath:db/migration/V5__world_summaries.sql,classpath:db/migration/V7__user_withdrawal.sql,classpath:db/migration/V8__legal_consents.sql,classpath:db/migration/V9__payment_refund_intake.sql","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
class PaymentMigrationTest {
    @Test void initialSchemaMatchesHibernateAndMigrationCanBeAppliedTwice() {
        var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:legacy-payment;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", ""));
        jdbc.execute("create table users(id bigint not null primary key)");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c -> {
            var script=new org.springframework.core.io.ClassPathResource("db/migrations/20261026-payapp-payments.sql");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,script);
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,script); return null;
        });
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='payment_orders'",String.class))
                .contains("input_hash","notification_token","credit_grant_id","request_deadline","version").doesNotContain("buyer_phone");
        assertThat(jdbc.queryForObject("select count(*) from payment_orders",Long.class)).isZero();
    }
}
