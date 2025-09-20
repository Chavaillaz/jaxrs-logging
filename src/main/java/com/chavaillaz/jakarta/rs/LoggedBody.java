package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Target.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Target.RESPONSE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Configuration for body logging of HTTP requests or responses.
 */
@Documented
@NameBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@Repeatable(Logged.class)
public @interface LoggedBody {

    /**
     * Indicates how the request or response body must be logged.
     * <p>
     * Do not activate it when expecting large payloads to avoid any performance or memory issue.
     * <p>
     * Note that for {@link LogType#MDC}, the request or response body will be stored in the request context
     * in order to be retrieved and stored as MDC when logging the processing log line.
     *
     * @return The types of logging to be done
     */
    LogType[] value() default {};

    /**
     * Limits the size of the request or response body to be logged (if activated).
     * <p>
     * By default, no limit is applied (note that it can lead to performance or memory issues).
     *
     * @return The maximum size of the body to be logged in bytes
     */
    int limit() default -1;

    /**
     * Indicates which filters must be applied before logging the request or response body.
     *
     * @return The list of filters to be applied
     */
    Class<? extends LoggedBodyFilter>[] filters() default {};

    /**
     * Indicates whether the logging configuration must be applied to the request, the response, or both.
     *
     * @return The targets to which the logging configuration must be applied
     */
    Target[] targets() default {REQUEST, RESPONSE};

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
     * Target of the logging configuration.
     */
    enum Target {

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
