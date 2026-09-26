package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.NO_LIMIT;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Configuration for body logging of HTTP requests or responses.
 * <p>
 * Declared as the content of {@link Logged} ({@code @Logged(@LoggedBody(...))}), or on its own, once or
 * repeated: either way, it activates the logging of the requests of the resource, as {@code @Logged} does.
 * <p>
 * A body is captured while its entity is read or written, which a resource method taking it as a stream - an
 * {@code InputStream} or a {@code Reader} parameter - does once the providers are done with it: such a body is
 * logged once the method read it to its end or closed it, and at the latest once the request is answered, as
 * far as the method read it. Set a {@link #limit()} for a resource taking large uploads that way, as for any
 * other.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@Repeatable(Logged.class)
public @interface LoggedBody {

    /**
     * Indicates how the request or response body must be logged, none logging no body at all.
     * <p>
     * A body is captured in memory by default, and a request body logged as {@link LogType#MDC} is kept until
     * the request completes, to be put in MDC for its {@code "Processed ..."} line: set a {@link #limit()} for
     * a resource accepting or returning large payloads.
     *
     * @return The types of logging to be done
     */
    LogType[] value() default {};

    /**
     * Limits the size of the request or response body to be logged (if activated).
     * <p>
     * By default, no limit is applied (note that it can lead to performance or memory issues). A body cut
     * short by the limit is logged with {@link BoundedLoggedBodyCapture#TRUNCATION_MARKER} appended, so it
     * is never mistaken for a complete one. A limit below {@code -1} is invalid: the resource then logs no
     * body at all, which is reported once, as an error.
     *
     * @return The maximum size of the body to be logged in bytes, or {@code -1} for no limit
     */
    int limit() default NO_LIMIT;

    /**
     * Indicates which filters must be applied before logging the request or response body.
     * <p>
     * Applied in the order declared here, so filters that depend on one another's output (for example one
     * masking a value another then truncates) run predictably.
     *
     * @return The list of filters to be applied
     */
    Class<? extends LoggedBodyFilter>[] filters() default {};

    /**
     * Indicates whether the logging configuration must be applied to the request, the response, or both.
     *
     * @return The targets to which the logging configuration must be applied
     */
    Direction[] targets() default {REQUEST, RESPONSE};

    /**
     * Type of logging to be applied to the request and response body.
     */
    enum LogType {

        /**
         * Writes the element as a new log line.
         */
        LOG,

        /**
         * Writes the element as MDC field of the processed log line from {@link LoggedFilter}.
         */
        MDC

    }

    /**
     * Direction (request or response) targeted by the logging configuration.
     */
    enum Direction {

        /**
         * Apply the logging configuration to the request body.
         */
        REQUEST,

        /**
         * Apply the logging configuration to the response body.
         */
        RESPONSE

    }

}
