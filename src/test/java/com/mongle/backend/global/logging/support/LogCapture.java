package com.mongle.backend.global.logging.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 비동기 스레드의 로그를 안전하게 수집하고 테스트 종료 후 기존 설정을 복구한다. */
public final class LogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    private final Logger logger;
    private final Level previous;
    private final ConcurrentLinkedQueue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();

    public LogCapture(Class<?> type) {
        logger = (Logger) LoggerFactory.getLogger(type);
        previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        setContext(logger.getLoggerContext());
        start();
        logger.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing();
        events.add(event);
    }

    public List<String> messages() { return events.stream().map(ILoggingEvent::getFormattedMessage).toList(); }
    public List<ILoggingEvent> events() { return List.copyOf(events); }

    @Override
    public void close() {
        logger.detachAppender(this);
        logger.setLevel(previous);
        stop();
    }
}
