package com.example.resilience.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Captures log events for a logger at DEBUG level for the duration of a test. Use in a
 * try-with-resources block; {@link #close()} detaches the appender and restores the level.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousLevel;

    public LogCapture(String loggerName) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName);
        this.previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public long count(String substring) {
        return messages().stream().filter(m -> m.contains(substring)).count();
    }

    public boolean any(String substring) {
        return messages().stream().anyMatch(m -> m.contains(substring));
    }

    /** True if some captured event whose message contains {@code substring} carries the given MDC entry. */
    public boolean anyWithMdc(String substring, String mdcKey, String mdcValue) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains(substring))
                .anyMatch(e -> mdcValue.equals(e.getMDCPropertyMap().get(mdcKey)));
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }
}
