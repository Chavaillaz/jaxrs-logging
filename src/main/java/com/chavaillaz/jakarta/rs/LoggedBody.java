package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Configuration for body logging of HTTP requests or responses.
 * <p>
 * Only takes effect on a resource {@link Logged} activates, either as its content
 * ({@code @Logged(@LoggedBody(...))}) or next to it on the resource method or class. Declared once on a
 * resource that is not {@code @Logged}, it activates nothing: JAX-RS binds {@link LoggedFilter} to
 * {@code @Logged}, which the compiler only synthesizes from several repeated {@code @LoggedBody}.
 * <p>
 * A body is captured while its entity is read or written, which a resource method taking it as a stream - an
 * {@code InputStream} or a {@code Reader} parameter - only does once the providers are done with it: such a
 * body is not logged, and nothing of it is buffered either.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@Repeatable(Logged.class)
public @interface LoggedBody {

    /**
     * Indicates how the request or response body must be logged.
     * <p>
     * Do not activate it when expecting large payloads to avoid any performance or memory issue.
     * <p>
     * Note that for {@link LogType#MDC} on the request, the body will be stored in the request context
     * in order to be retrieved and stored as MDC when logging the processing log line.
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
    int limit() default LoggedBodyCapture.NO_LIMIT;

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
