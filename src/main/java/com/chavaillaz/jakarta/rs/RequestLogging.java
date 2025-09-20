package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Configuration for body logging of HTTP requests.
 */
@Documented
@NameBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface RequestLogging {

    /**
     * Indicates how the request body must be logged.
     * <p>
     * Do not activate it when expecting large payloads to avoid any performance or memory issue.
     * <p>
     * Note that for {@link Logged.LogType#MDC}, the request body will be stored in the request context
     * in order to be retrieved and stored as MDC when logging the processing log line.
     *
     * @return The types of logging to be done
     */
    Logged.LogType[] value() default {};

    /**
     * Limits the size of the request body to be logged (if activated).
     * <p>
     * By default, no limit is applied (note that it can lead to performance or memory issues).
     *
     * @return The maximum size of the body to be logged in bytes
     */
    int limit() default -1;

    /**
     * Indicates which filters must be applied before logging the request body.
     *
     * @return The list of filters to be applied
     */
    Class<? extends BodyFilter>[] filters() default {};

}
