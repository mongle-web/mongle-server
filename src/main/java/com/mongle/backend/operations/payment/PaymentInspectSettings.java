package com.mongle.backend.operations.payment;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.util.*;

import javax.sql.DataSource;

/** 기존 앱 계정·검증 TLS를 사용하며 쓰기 기능 플래그·PG 키·마이그레이션 설정은 읽지 않는다. */
record PaymentInspectSettings(
        String url, String user, String password, String truststore, String truststorePassword) {
    static PaymentInspectSettings fromEnvironment(Map<String, String> environment) {
        if (!"true".equals(environment.get("MONGLE_PAYMENT_INSPECT_ENABLED")))
            throw new PaymentInspectCommand.Failure("OPERATOR_COMMAND_DISABLED");
        try {
            String url = required(environment, "DB_URL");
            String user = required(environment, "DB_USERNAME");
            var target = URI.create(url.substring("jdbc:".length()));
            String database = required(environment, "MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE");
            var trust = URI.create(required(environment, "DB_TRUSTSTORE_URL"));
            if (!url.startsWith("jdbc:mysql://")
                    || !"mysql".equals(target.getScheme())
                    || !required(environment, "MONGLE_PAYMENT_INSPECT_EXPECTED_HOST")
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
                    || !"file".equals(trust.getScheme())
                    || trust.getAuthority() != null
                    || trust.getPath() == null
                    || !trust.getPath().startsWith("/")
                    || trust.getQuery() != null
                    || trust.getFragment() != null) throw new IllegalArgumentException();
            return new PaymentInspectSettings(
                    url,
                    user,
                    required(environment, "DB_PASSWORD"),
                    trust.toString(),
                    required(environment, "DB_TRUSTSTORE_PASSWORD"));
        } catch (RuntimeException failure) {
            throw new PaymentInspectCommand.Failure("INVALID_CONFIGURATION");
        }
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException();
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
        properties.setProperty("socketTimeout", "5000");
        properties.setProperty("connectionTimeZone", "UTC");
        properties.setProperty("readOnlyPropagatesToServer", "true");
        properties.setProperty("useLocalSessionState", "false");
        source.setConnectionProperties(properties);
        return source;
    }

    @Override
    public String toString() {
        return "PaymentInspectSettings[redacted]";
    }
}
