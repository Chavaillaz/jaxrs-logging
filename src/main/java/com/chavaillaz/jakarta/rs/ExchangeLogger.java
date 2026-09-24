package com.chavaillaz.jakarta.rs;

import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import jakarta.ws.rs.core.MultivaluedMap;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;

/**
 * Writes the lines {@link LoggedFilter} logs an exchange with - {@code Received ...} once a request is read,
 * {@code Processed ...} once it is answered - and returns the identifier they were logged under to the
 * caller, the way its configuration says to.
 * <p>
 * Only writes: when each line is due, and what goes in MDC along with it, is the provider's to decide.
 */
final class ExchangeLogger {

    private final Logger log;
    private final LoggedFilterConfiguration configuration;

    /**
     * Creates the writer of the lines of a provider.
     *
     * @param log           The logger of the provider
     * @param configuration The configuration of the provider
     */
    ExchangeLogger(Logger log, LoggedFilterConfiguration configuration) {
        this.log = log;
        this.configuration = configuration;
    }

    /**
     * Logs a request received, with its body on the following lines if it has one.
     *
     * @param method The method of the request
     * @param uri    The URI of the request
     * @param body   The body of the request, blank if it is not logged
     */
    void received(String method, String uri, String body) {
        log.info("Received {} {}{}{}", method, uri, isNotBlank(body) ? LF : EMPTY, body);
    }

    /**
     * Logs a request answered, with the body of the response on the following lines if it has one, at the
     * level the configuration gives to its status (see
     * {@link LoggedFilterConfiguration.Builder#responseLevel(java.util.function.IntFunction)}).
     * <p>
     * The status is the one logged in MDC rather than one taken from the response context, so the level
     * still matches the status actually logged when the request completes where no response context is at
     * hand.
     *
     * @param method   The method of the request
     * @param uri      The URI of the request
     * @param status   The status the request was answered with, {@code null} if unknown
     * @param duration The time taken to answer the request, in milliseconds
     * @param body     The body of the response, blank if it is not logged
     */
    void processed(String method, String uri, String status, String duration, String body) {
        log.atLevel(configuration.responseLevel(NumberUtils.toInt(status)))
                .log("Processed {} {} with status {} in {}ms{}{}", method, uri, status, duration, isNotBlank(body) ? LF : EMPTY, body);
    }

    /**
     * Returns the identifier a request was logged under to the caller, in the header the configuration
     * names (see {@link LoggedFilterConfiguration.Builder#withoutReturnedRequestId()} for why).
     * <p>
     * Left alone if the response already carries the header, whatever its casing, so an application (or a
     * gateway in front of it) deliberately setting its own is not overwritten by this one.
     *
     * @param headers   The headers of the response to be sent
     * @param requestId The identifier the request was logged under, {@code null} if unknown
     */
    void returnRequestId(MultivaluedMap<String, Object> headers, String requestId) {
        String header = configuration.returnedRequestIdHeader();
        // HTTP header names are case-insensitive, but the response header map is only a MultivaluedMap in
        // the JAX-RS API, so a container backing it with a case-sensitive one would otherwise send the
        // header twice with two different values
        if (header != null && isNotBlank(requestId) && headers.keySet().stream().noneMatch(header::equalsIgnoreCase)) {
            headers.putSingle(header, requestId);
        }
    }

}
