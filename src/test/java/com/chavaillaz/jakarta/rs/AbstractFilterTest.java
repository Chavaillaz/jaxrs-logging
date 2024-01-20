package com.chavaillaz.jakarta.rs;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.MDC;

public abstract class AbstractFilterTest {

    protected static final InMemoryAppender LIST_APPENDER = InMemoryAppender.createDefaultAppender();

    @BeforeAll
    static void registerListAppender() {
        LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = loggerContext.getConfiguration();
        LoggerConfig rootLoggerConfig = configuration.getLoggerConfig("");
        rootLoggerConfig.addAppender(LIST_APPENDER, Level.ALL, null);
    }

    @BeforeEach
    void setupTest() {
        MDC.clear();
        LIST_APPENDER.getMessages().clear();
        LIST_APPENDER.start();
    }

}
