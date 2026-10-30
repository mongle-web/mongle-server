package com.mongle.backend.operations.payment;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

/** 임시 MySQL 8.4의 TLS·DML 계정으로 같은 조회 계약과 읽기 전용 강제를 검증한다. */
class PaymentInspectorMysql84Test extends PaymentInspectorContract {
    @Override
    DataSource dataSource() {
        int port = Integer.parseInt(System.getenv("MONGLE_MYSQL_TEST_PORT"));
        if (port < 1 || port > 65535)
            throw new IllegalStateException("Disposable fixture required");
        var env = new HashMap<>(System.getenv());
        env.put("MONGLE_PAYMENT_INSPECT_ENABLED", "true");
        env.put("MONGLE_PAYMENT_INSPECT_EXPECTED_HOST", "127.0.0.1");
        env.put("MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE", "credit_test");
        env.put("DB_URL", "jdbc:mysql://127.0.0.1:" + port + "/credit_test");
        env.put("DB_USERNAME", "mongle_app");
        env.put("DB_PASSWORD", System.getenv("MONGLE_MYSQL_TEST_PASSWORD"));
        return PaymentInspectSettings.fromEnvironment(env).dataSource();
    }

    @Test
    void mysqlEnforcesReadOnlyTransactionAndEveryQueryHasBoundedTimeAndRows() throws Exception {
        String target = order("READY");
        paid(target);
        var checked = new AtomicBoolean();
        var observed =
                new ObservedInspectionDataSource(
                        source,
                        (connection, sql) -> {
                            if (!checked.compareAndSet(false, true)) return;
                            try {
                                assertThat(connection.isReadOnly()).isTrue();
                                assertThat(connection.getTransactionIsolation())
                                        .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                                try (var statement = connection.createStatement();
                                        var row =
                                                statement.executeQuery(
                                                        "select @@transaction_read_only")) {
                                    assertThat(row.next()).isTrue();
                                    assertThat(row.getInt(1)).isEqualTo(1);
                                }
                                assertThatThrownBy(
                                                () -> {
                                                    try (var statement =
                                                            connection.createStatement()) {
                                                        statement.executeUpdate(
                                                                "update credit_accounts set"
                                                                    + " available=available+1 where"
                                                                    + " user_id="
                                                                        + user);
                                                    }
                                                })
                                        .isInstanceOf(SQLException.class);
                            } catch (SQLException failure) {
                                throw new AssertionError(failure);
                            }
                        });
        var before = snapshot();
        new PaymentInspector(observed, CLOCK).inspect(target);
        assertThat(checked).isTrue();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(observed.queries)
                .hasSizeLessThanOrEqualTo(10)
                .allSatisfy(sql -> assertThat(sql.stripLeading()).startsWithIgnoringCase("select"));
        assertThat(observed.timeouts).allMatch(value -> value >= 1 && value <= 5);
        assertThat(observed.limits).containsOnly(32);
        try (var connection = source.getConnection()) {
            assertThat(connection.isReadOnly()).isFalse();
        }
    }

    @Test
    void concurrentPaymentCommitDoesNotMixNewGrantsIntoTheOldOrderSnapshotOrWaitForLocks()
            throws Exception {
        String target = order("READY");
        var committed = new AtomicBoolean();
        var observed =
                new ObservedInspectionDataSource(
                        source,
                        (connection, sql) -> {
                            if (!sql.contains("from payment_orders")
                                    || !committed.compareAndSet(false, true)) return;
                            try (var executor = Executors.newSingleThreadExecutor()) {
                                try {
                                    executor.submit(
                                                    () ->
                                                            new TransactionTemplate(
                                                                            new DataSourceTransactionManager(
                                                                                    source))
                                                                    .executeWithoutResult(
                                                                            status -> paid(target)))
                                            .get(5, TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                        });
        var first = json.valueToTree(new PaymentInspector(observed, CLOCK).inspect(target));
        matched(first, "UNCONFIRMED");
        assertThat(first.path("purchase").isNull()).isTrue();
        assertThat(first.at("/account/available").asLong()).isZero();
        var after = inspect(target);
        matched(after, "CONSISTENT");
        assertThat(after.at("/purchase/available").asLong()).isEqualTo(10);
    }
}
