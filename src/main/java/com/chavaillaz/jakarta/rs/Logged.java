package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Annotation activating the filter {@link LoggedFilter}
 * in order to log incoming requests received by a JAX-RS resource.
 */
@Documented
@NameBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface Logged {

    /**
     * Indicates how the request body must be logged.
     *
     * @return The types of logging to be done
     */
    LogType[] requestBody() default {};

    /**
     * Indicates how the response body must be logged.
     *
     * @return The types of logging to be done
     */
    LogType[] responseBody() default {};

    /**
     * Indicates which filters must be applied before logging the request or response body.
     *
     * @return The list of filters to be applied
     */
    Class<? extends LoggedBodyFilter>[] filtersBody() default {};

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

}
