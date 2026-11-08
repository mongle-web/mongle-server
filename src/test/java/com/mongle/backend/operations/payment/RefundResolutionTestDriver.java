package com.mongle.backend.operations.payment;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;

public final class RefundResolutionTestDriver {
    private RefundResolutionTestDriver() {}

    public static Map<String, Object> run(DataSource source, Clock clock, String... args) throws SQLException {
        return new RefundResolver(source, clock).execute(RefundResolutionRequest.parse(args));
    }
}
