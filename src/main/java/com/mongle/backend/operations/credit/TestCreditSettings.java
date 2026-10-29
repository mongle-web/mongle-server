package com.mongle.backend.operations.credit;

import com.mongle.backend.domain.credit.CreditProperties;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

/** 운영 앱의 DML 계정과 TLS만 사용한다. 프로필·마이그레이션 비밀값은 읽지 않는다. */
record TestCreditSettings(
        String url, String user, String password, String truststore, String truststorePassword) {
    static TestCreditSettings fromEnvironment(Map<String, String> environment) {
        if (!"true".equals(environment.get("MONGLE_TEST_CREDIT_ENABLED")))
            throw new TestCreditCommand.Failure("OPERATOR_COMMAND_DISABLED");
        if (!"true".equals(environment.get("MONGLE_CREDIT_ENABLED")))
            throw new TestCreditCommand.Failure("CREDIT_503_1");
        if (!"NO_EXPIRY"
                .equals(environment.getOrDefault("MONGLE_CREDIT_EXPIRY_POLICY", "NO_EXPIRY")))
            throw new TestCreditCommand.Failure("INVALID_CONFIGURATION");
        String url = required(environment, "DB_URL");
        String user = required(environment, "DB_USERNAME");
        try {
            var target = URI.create(url.substring("jdbc:".length()));
            var truststore = URI.create(required(environment, "DB_TRUSTSTORE_URL"));
            String database = required(environment, "MONGLE_TEST_CREDIT_EXPECTED_DATABASE");
            if (!url.startsWith("jdbc:mysql://")
                    || !"mysql".equals(target.getScheme())
                    || !required(environment, "MONGLE_TEST_CREDIT_EXPECTED_HOST")
                            .equals(target.getHost())
                    || target.getUserInfo() != null
                    || target.getQuery() != null
                    || target.getFragment() != null
                    || target.getPort() == 0
                    || target.getPort() < -1
                    || target.getPort() > 65535
                    || !database.matches("[a-z][a-z0-9_]{0,63}")
                    || !("/" + database).equals(target.getPath())
                    || !"mongle_app".equals(user)
                    || !"file".equals(truststore.getScheme())
                    || truststore.getAuthority() != null
                    || truststore.getPath() == null
                    || !truststore.getPath().startsWith("/")
                    || truststore.getQuery() != null
                    || truststore.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new TestCreditCommand.Failure("INVALID_CONFIGURATION");
        }
        return new TestCreditSettings(
                url,
                user,
                required(environment, "DB_PASSWORD"),
                required(environment, "DB_TRUSTSTORE_URL"),
                required(environment, "DB_TRUSTSTORE_PASSWORD"));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new TestCreditCommand.Failure("INVALID_CONFIGURATION");
        return value;
    }

    DataSource dataSource() {
        var source = new DriverManagerDataSource(url, user, password);
        var properties = new Properties();
        properties.setProperty("sslMode", "VERIFY_IDENTITY");
        properties.setProperty("trustCertificateKeyStoreUrl", truststore);
        properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
        properties.setProperty("trustCertificateKeyStorePassword", truststorePassword);
        properties.setProperty("fallbackToSystemTrustStore", "false");
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "15000");
        source.setConnectionProperties(properties);
        return source;
    }

    CreditProperties creditProperties() {
        return new CreditProperties(
                true, CreditProperties.ExpiryPolicy.NO_EXPIRY, null, null, null, null, null);
    }

    @Override
    public String toString() {
        return "TestCreditSettings[redacted]";
    }
}
