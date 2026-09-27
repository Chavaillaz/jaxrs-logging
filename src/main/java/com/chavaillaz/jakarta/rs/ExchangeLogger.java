package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedFeatureConfiguration.levelOf;
import static com.chavaillaz.jakarta.rs.internal.LoggingGuard.safely;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import jakarta.ws.rs.core.MultivaluedMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * Writes the lines a request is logged with - {@code Received ...} once read, {@code Processed ...} once
 * answered - and returns the identifier they were logged under to the caller, as configured. When each line is
 * due is the filter's to decide.
 */
final class ExchangeLogger {

    private final Logger log;
    private final LoggedFeatureConfiguration configuration;

    /**
     * Creates the writer of the lines of a feature.
     *
     * @param log           The logger of the feature
     * @param configuration The configuration of the feature
     */
    ExchangeLogger(Logger log, LoggedFeatureConfiguration configuration) {
        this.log = log;
        this.configuration = configuration;
    }

    /**
     * Logs a request received, with its body on the following lines if it has one.
     *
     * @param method The method of the request, {@code null} if unknown
     * @param uri    The URI of the request, {@code null} if unknown
     * @param body   The body of the request, blank if it is not logged
     */
    void received(@Nullable String method, @Nullable String uri, String body) {
        log.info("Received {} {}{}{}", method, uri, isNotBlank(body) ? LF : EMPTY, body);
    }

    /**
     * Logs a request answered, with the body of the response on the following lines if it has one, at the level
     * the configuration gives its status, or the default one of the status if that fails.
     *
     * @param method   The method of the request, {@code null} if unknown
     * @param uri      The URI of the request, {@code null} if unknown
     * @param status   The status the request was answered with, {@code 0} if unknown
     * @param duration The time taken to answer the request, in milliseconds
     * @param body     The body of the response, blank if it is not logged
     */
    void processed(@Nullable String method, @Nullable String uri, int status, long duration, String body) {
        Level level = safely(log, "Unable to get the level of the request, its default one is used instead",
                () -> configuration.responseLevelOf(status), levelOf(status));
        log.atLevel(level)
                .log("Processed {} {} with status {} in {}ms{}{}", method, uri, status, duration, isNotBlank(body) ? LF : EMPTY, body);
    }

    /**
     * Returns the identifier a request was logged under to the caller, in the header the configuration names,
     * unless the response carries that header already, whatever its casing.
     *
     * @param headers   The headers of the response to be sent
     * @param requestId The identifier the request was logged under, {@code null} if unknown
     */
    void returnRequestId(MultivaluedMap<String, Object> headers, @Nullable String requestId) {
        String header = configuration.getReturnedRequestIdHeader();
        // Compared without regard to case, as a container may back the response headers with a case-sensitive map
        if (header != null && isNotBlank(requestId) && headers.keySet().stream().noneMatch(header::equalsIgnoreCase)) {
            headers.putSingle(header, requestId);
        }
    }

}
