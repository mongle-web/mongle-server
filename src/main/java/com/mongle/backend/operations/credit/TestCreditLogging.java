package com.mongle.backend.operations.credit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/** JDBC/JPA 예외에 포함될 수 있는 URL·SQL·인증값 대신 명령의 정제된 JSON만 출력한다. */
final class TestCreditLogging implements AutoCloseable {
    private final Map<Logger, Level> levels = new HashMap<>();

    TestCreditLogging() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context))
            throw new TestCreditCommand.Failure("UNSUPPORTED_LOGGING_BACKEND");
        for (var logger : context.getLoggerList()) {
            levels.put(logger, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
    }

    @Override
    public void close() {
        levels.forEach(Logger::setLevel);
    }
}
