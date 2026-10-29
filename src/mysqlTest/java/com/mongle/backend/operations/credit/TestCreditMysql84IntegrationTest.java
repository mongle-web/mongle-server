package com.mongle.backend.operations.credit;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

/** 공통 지급 계약을 실제 DML 계정·TLS 연결에서 다시 실행한다. 운영 DB는 허용하지 않는다. */
class TestCreditMysql84IntegrationTest extends TestCreditContextIntegrationTest {
    private Map<String, String> fixture() {
        int port = Integer.parseInt(System.getenv("MONGLE_MYSQL_TEST_PORT"));
        if (port < 1 || port > 65535)
            throw new IllegalStateException("Disposable fixture required");
        var env = new HashMap<>(System.getenv());
        env.put("MONGLE_TEST_CREDIT_ENABLED", "true");
        env.put("MONGLE_CREDIT_ENABLED", "true");
        env.put("MONGLE_CREDIT_EXPIRY_POLICY", "NO_EXPIRY");
        env.put("MONGLE_TEST_CREDIT_EXPECTED_HOST", "127.0.0.1");
        env.put("MONGLE_TEST_CREDIT_EXPECTED_DATABASE", "credit_test");
        env.put("DB_URL", "jdbc:mysql://127.0.0.1:" + port + "/credit_test");
        env.put("DB_USERNAME", "mongle_app");
        env.put("DB_PASSWORD", System.getenv("MONGLE_MYSQL_TEST_PASSWORD"));
        return env;
    }

    @Override
    DataSource dataSource() {
        return TestCreditSettings.fromEnvironment(fixture()).dataSource();
    }

    @Override
    void ddl(String sql) {
        // 롤백 검증용 CHECK만 별도 fixture migrator로 설치한다. 명령의 앱 계정은 DDL을 못 한다.
        var migrator =
                (DriverManagerDataSource)
                        TestCreditSettings.fromEnvironment(fixture()).dataSource();
        migrator.setUsername("mongle_migrator");
        migrator.setPassword(System.getenv("MONGLE_MYSQL_MIGRATOR_PASSWORD"));
        new JdbcTemplate(migrator)
                .execute(
                        sql.replace(
                                "drop constraint operator_test_reject",
                                "drop check operator_test_reject"));
    }

    @Test
    void standaloneContextUsesVerifiedTlsAndDmlOnlyIdentity() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("8.4.");
        assertThat(jdbc.queryForMap("show session status like 'Ssl_cipher'").get("Value"))
                .asString()
                .isNotBlank();
        assertThat(jdbc.queryForList("show grants", String.class))
                .noneMatch(
                        grant ->
                                grant.contains("ALL PRIVILEGES")
                                        || grant.contains("CREATE")
                                        || grant.contains("ALTER")
                                        || grant.contains("DROP"));
        assertThatThrownBy(() -> jdbc.execute("create table forbidden_test_credit(id bigint)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
