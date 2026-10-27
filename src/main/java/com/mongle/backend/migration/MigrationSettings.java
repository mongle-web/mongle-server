package com.mongle.backend.migration;

import java.net.URI;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Deliberately independent of Spring profiles and the application's DML credentials. */
public record MigrationSettings(String url, String user, String password, String truststore,
                                String truststorePassword, String database) {
    public static MigrationSettings fromEnvironment(Map<String, String> environment) {
        String url = required(environment, "DB_URL");
        String host = required(environment, "EXPECTED_HOST");
        String database = required(environment, "EXPECTED_DATABASE");
        String user = required(environment, "DB_USERNAME");
        try {
            URI target = URI.create(url.substring("jdbc:".length()));
            URI trust = URI.create(required(environment, "TRUSTSTORE_URL"));
            if (!url.startsWith("jdbc:mysql://") || !"mysql".equals(target.getScheme())
                    || !host.equals(target.getHost()) || target.getUserInfo() != null
                    || target.getQuery() != null || target.getFragment() != null
                    || target.getPort() == 0 || target.getPort() < -1 || target.getPort() > 65535
                    || !database.matches("[a-z][a-z0-9_]{0,63}")
                    || !("/" + database).equals(target.getPath())
                    || !"mongle_migrator".equals(user)
                    || !"file".equals(trust.getScheme()) || trust.getAuthority() != null
                    || trust.getPath() == null || !trust.getPath().startsWith("/")
                    || trust.getQuery() != null || trust.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new MigrationFailure("INVALID_CONFIGURATION");
        }
        return new MigrationSettings(url, user, required(environment, "DB_PASSWORD"),
                required(environment, "TRUSTSTORE_URL"), required(environment, "TRUSTSTORE_PASSWORD"), database);
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get("MONGLE_MIGRATION_" + key);
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            throw new MigrationFailure("INVALID_CONFIGURATION");
        }
        return value;
    }

    public DataSource dataSource() {
        var source = new DriverManagerDataSource(url, user, password);
        var properties = new Properties();
        properties.setProperty("sslMode", "VERIFY_IDENTITY");
        properties.setProperty("trustCertificateKeyStoreUrl", truststore);
        properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
        properties.setProperty("trustCertificateKeyStorePassword", truststorePassword);
        properties.setProperty("fallbackToSystemTrustStore", "false");
        properties.setProperty("connectTimeout", "5000");
        // CD's systemd RuntimeMaxSec=240 is the primary deadline (scripts/cd/deploy.py).
        // Keep this fallback above 240s and below the deployer's 270s wait.
        properties.setProperty("socketTimeout", "250000");
        source.setConnectionProperties(properties);
        return source;
    }

    @Override public String toString() { return "MigrationSettings[redacted]"; }
}
