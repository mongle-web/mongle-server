package com.mongle.backend.domain.credit;

import static org.assertj.core.api.Assertions.*;

import com.mongle.backend.support.NativeMysqlSupport;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.security.KeyStore;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

@ActiveProfiles(value = "mysql", inheritProfiles = false)
class Mysql84CreditIntegrationTest extends CreditReservationIntegrationTest {
    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        NativeMysqlSupport.register(registry, "credit_test");
    }

    @Test
    void validatesSchemaAndUsesTlsWithOnlyDmlPrivileges() {
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("8.4.");
        assertThat(jdbc.queryForMap("show session status like 'Ssl_cipher'").get("Value"))
                .asString()
                .isNotBlank();
        assertThat(jdbc.queryForList("SHOW GRANTS", String.class))
                .noneMatch(
                        grant ->
                                grant.contains("ALL PRIVILEGES")
                                        || grant.contains("CREATE")
                                        || grant.contains("ALTER")
                                        || grant.contains("DROP"));
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE forbidden_ddl(id BIGINT)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from information_schema.tables where"
                                    + " table_schema=database()",
                                Integer.class))
                .isEqualTo(33);
    }

    @Test
    void mysqlProfileRequiresTrustedServerIdentity() throws Exception {
        HikariDataSource pool = (HikariDataSource) dataSource;
        assertThat(pool.getDataSourceProperties().getProperty("sslMode"))
                .isEqualTo("VERIFY_IDENTITY");
        assertThat(pool.getDataSourceProperties().getProperty("fallbackToSystemTrustStore"))
                .isEqualTo("false");
        Properties properties = new Properties();
        properties.putAll(pool.getDataSourceProperties());
        properties.setProperty("user", pool.getUsername());
        properties.setProperty("password", pool.getPassword());
        // A valid empty trust store must fail; encryption alone must not be enough.
        var untrusted = Files.createTempFile("mongle-untrusted-", ".p12");
        try {
            KeyStore empty = KeyStore.getInstance("PKCS12");
            char[] password = "test-store-only".toCharArray();
            empty.load(null, password);
            try (var output = Files.newOutputStream(untrusted)) {
                empty.store(output, password);
            }
            properties.setProperty("trustCertificateKeyStoreUrl", untrusted.toUri().toString());
            properties.setProperty("trustCertificateKeyStorePassword", new String(password));
            assertThatThrownBy(
                            () -> {
                                try (var ignored =
                                        DriverManager.getConnection(
                                                pool.getJdbcUrl(), properties)) {
                                    fail("Untrusted TLS connection unexpectedly succeeded");
                                }
                            })
                    .isInstanceOf(SQLException.class);
        } finally {
            Files.deleteIfExists(untrusted);
        }
    }
}
