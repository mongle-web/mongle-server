package com.mongle.backend.domain.payment;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payment-schema;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:db/schema-initial.sql,classpath:db/migration/V3__image_regeneration_policy.sql,classpath:db/migration/V4__recent_interpretations.sql","spring.jpa.hibernate.ddl-auto=validate"})
@ActiveProfiles("test")
class PaymentMigrationTest {
    @Autowired JdbcTemplate jdbc;
    @Test void initialSchemaMatchesHibernateAndMigrationCanBeAppliedTwice() {
        for(String table:java.util.List.of("payment_notifications","payment_transaction_bindings","payment_orders","credit_payment_holds")) jdbc.execute("drop table "+table);
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
