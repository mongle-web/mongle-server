package com.mongle.backend.operations.payment;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.function.BiConsumer;

import javax.sql.DataSource;

/** 테스트 전용: SQL과 연결 경계를 관찰하고 실제 조회 후 별도 연결 갱신을 재현한다. */
final class ObservedInspectionDataSource extends AbstractDataSource {
    final DataSource delegate;
    final BiConsumer<Connection, String> afterQuery;
    final List<String> queries = new ArrayList<>();
    final List<Integer> timeouts = new ArrayList<>(), limits = new ArrayList<>();

    ObservedInspectionDataSource(DataSource delegate, BiConsumer<Connection, String> afterQuery) {
        this.delegate = delegate;
        this.afterQuery = afterQuery;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return wrap(delegate.getConnection(user, password));
    }

    Connection wrap(Connection real) {
        return (Connection)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[] {Connection.class},
                        (proxy, method, args) -> {
                            Object value = invoke(real, method, args);
                            if (method.getName().equals("prepareStatement")
                                    && args[0] instanceof String sql) {
                                queries.add(sql);
                                var statement = (PreparedStatement) value;
                                return Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {PreparedStatement.class},
                                        (p, m, a) -> {
                                            if (m.getName().equals("setQueryTimeout"))
                                                timeouts.add((Integer) a[0]);
                                            if (m.getName().equals("setMaxRows"))
                                                limits.add((Integer) a[0]);
                                            Object result = invoke(statement, m, a);
                                            if (m.getName().equals("executeQuery"))
                                                afterQuery.accept(real, sql);
                                            return result;
                                        });
                            }
                            return value;
                        });
    }

    private static Object invoke(Object target, Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
