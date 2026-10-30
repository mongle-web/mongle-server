package com.mongle.backend.operations.payment;

import ch.qos.logback.classic.*;

import org.slf4j.LoggerFactory;

import java.util.*;

/** SQL/JDBC 예외 대신 정제된 명령 결과만 출력한다. 새 로거도 root의 OFF를 상속한다. */
final class PaymentInspectLogging implements AutoCloseable {
    private final Map<Logger, Level> levels = new HashMap<>();

    PaymentInspectLogging() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context))
            throw new PaymentInspectCommand.Failure("UNSUPPORTED_LOGGING_BACKEND");
        for (var logger : context.getLoggerList()) {
            levels.put(logger, logger.getLevel());
            logger.setLevel(Level.OFF);
        }
    }

    public void close() {
        levels.forEach(Logger::setLevel);
    }
}
