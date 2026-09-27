package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.DEFAULT_LIMIT;
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
 * Logs the bodies of the requests of a resource, or of their responses, and activates the logging of its
 * requests as {@link Logged} does. Declared on its own, once or repeated, or inside {@link Logged}.
 * <p>
 * A body is captured while its entity is read or written. One the resource method reads as a stream - an
 * {@code InputStream} or a {@code Reader} parameter - is logged once the method read it to its end or closed
 * it, and at the latest once the request is answered, as far as the method read it.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@Repeatable(Logged.class)
public @interface LoggedBody {

    /**
     * How the body is logged, empty to log none.
     * <p>
     * A body is captured in memory by default, up to the {@link #limit()}, and a request body logged as
     * {@link LogType#MDC} is kept until the request completes.
     *
     * @return The types of logging to be done
     */
    LogType[] value() default {};

    /**
     * Size of the body logged, beyond which it is cut and logged with
     * {@link BoundedLoggedBodyCapture#TRUNCATION_MARKER} appended. Defaults to
     * {@link LoggedBodyCapture#DEFAULT_LIMIT}, 64 KiB, as a body is buffered while captured; {@code -1} removes
     * the limit. A limit below {@code -1} is reported as an error, and the resource then logs no body.
     *
     * @return The maximum size of the body logged in bytes, {@code -1} for no limit
     */
    int limit() default DEFAULT_LIMIT;

    /**
     * Filters applied to the body before it is logged, in the order declared.
     *
     * @return The filters
     */
    Class<? extends LoggedBodyFilter>[] filters() default {};

    /**
     * Directions the configuration applies to. A configuration targeting a single direction wins over one
     * targeting both.
     *
     * @return The directions
     */
    Direction[] targets() default {REQUEST, RESPONSE};

    /**
     * How a body is logged.
     */
    enum LogType {

        /**
         * On a line of its own for a request, {@code "Received ..."}, and on the {@code "Processed ..."} line for
         * a response.
         */
        LOG,

        /**
         * As an MDC entry of the {@code "Processed ..."} line.
         */
        MDC

    }

    /**
     * Direction of the body a configuration applies to.
     */
    enum Direction {

        /**
         * The body of the request.
         */
        REQUEST,

        /**
         * The body of the response.
         */
        RESPONSE

    }

}
