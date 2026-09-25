package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

@DisplayName("Exchange logger")
class ExchangeLoggerTest extends AbstractFilterTest {

    final ExchangeLogger defaultLogger = logger(LoggedFilterConfiguration.defaults());

    static ExchangeLogger logger(LoggedFilterConfiguration configuration) {
        return new ExchangeLogger(LoggerFactory.getLogger(ExchangeLoggerTest.class), configuration);
    }

    static String lastMessage() {
        return lastEvent().getMessage().getFormattedMessage();
    }

    static LogEvent lastEvent() {
        return listAppender.getMessages().getLast();
    }

    @Test
    @DisplayName("Check a request is logged with its body on the following lines, if it has one")
    void checkReceived() {
        defaultLogger.received("GET", "/articles", "");
        assertEquals("Received GET /articles", lastMessage());

        defaultLogger.received("POST", "/articles", "{\"title\": \"News\"}");
        assertEquals("Received POST /articles\n{\"title\": \"News\"}", lastMessage());
    }

    @ParameterizedTest(name = "status {0} logged at {1}")
    @CsvSource({"200, INFO", "404, WARN", "503, ERROR", "0, INFO"})
    @DisplayName("Check an answered request is logged at the level its status is given by default")
    void checkProcessedLevel(int status, String expectedLevel) {
        defaultLogger.processed("GET", "/articles", status, 12, "");

        assertEquals("Processed GET /articles with status " + status + " in 12ms", lastMessage());
        assertEquals(expectedLevel, lastEvent().getLevel().name());
    }

    @Test
    @DisplayName("Check an answered request is logged at the level the configuration gives its status")
    void checkProcessedConfiguredLevel() {
        ExchangeLogger logger = logger(LoggedFilterConfiguration.builder()
                .responseLevel(status -> status == 404 ? Level.INFO : Level.ERROR)
                .build());

        logger.processed("GET", "/articles/42", 404, 3, "{\"error\": \"Not found\"}");

        assertEquals("Processed GET /articles/42 with status 404 in 3ms\n{\"error\": \"Not found\"}", lastMessage());
        assertEquals("INFO", lastEvent().getLevel().name());
    }

    @Test
    @DisplayName("Check an answered request is logged at its default level when the level function fails")
    void checkProcessedFailingLevel() {
        // Given
        ExchangeLogger logger = logger(LoggedFilterConfiguration.builder()
                .responseLevel(status -> {
                    throw new IllegalStateException("No level for " + status);
                })
                .build());

        // When
        logger.processed("GET", "/articles", 503, 7, "");

        // Then: the failure is reported, and the line logged all the same
        LogEvent processed = listAppender.findFirstMessage("Processed");
        assertNotNull(processed, "No Processed line was logged");
        assertEquals("Processed GET /articles with status 503 in 7ms", processed.getMessage().getFormattedMessage());
        assertEquals("ERROR", processed.getLevel().name());
        assertNotNull(listAppender.findFirstMessage("Unable to get the level of the request"));
    }

    @Test
    @DisplayName("Check the request identifier is returned in the header configured, unless already there")
    void checkRequestIdReturned() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        defaultLogger.returnRequestId(headers, "abc-123");
        assertEquals("abc-123", headers.getFirst(REQUEST_ID_HEADER));

        MultivaluedMap<String, Object> alreadySet = new MultivaluedHashMap<>();
        alreadySet.putSingle("x-request-id", "chosen-by-the-application");
        defaultLogger.returnRequestId(alreadySet, "abc-123");
        assertEquals(1, alreadySet.size());
        assertEquals("chosen-by-the-application", alreadySet.getFirst("x-request-id"));
    }

    @Test
    @DisplayName("Check no request identifier is returned when there is none, or when configured not to")
    void checkRequestIdNotReturned() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        defaultLogger.returnRequestId(headers, null);
        defaultLogger.returnRequestId(headers, " ");
        logger(LoggedFilterConfiguration.builder().withoutReturnedRequestId().build()).returnRequestId(headers, "abc-123");

        assertTrue(headers.isEmpty());
    }

}
