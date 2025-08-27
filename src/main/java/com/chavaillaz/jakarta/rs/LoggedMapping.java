package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotation defining a mapping from a parameter to an MDC entry
 * to be used by the {@link LoggedFilter} when logging a request.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@Repeatable(LoggedMappings.class)
public @interface LoggedMapping {

    /**
     * Type of field to be mapped to the given MDC key.
     *
     * @return The type of mapping
     */
    LogMappingType type();

    /**
     * Flag indicating if the mapping must be done automatically
     * (one to one, without changing the names of parameters) for the given type.
     *
     * @return {@code true} to automatically map the parameters, {@code false} otherwise
     */
    boolean auto() default false;

    /**
     * Prefix to be added to the MDC key.
     * Meant to be used with {@link #auto()} to avoid conflicts with other MDC entries.
     *
     * @return The prefix
     */
    String mdcPrefix() default "";

    /**
     * MDC key to which map any of the parameter names of the defined type.
     * Can be empty to ignore the mapping for the given parameters.
     *
     * @return The MDC key
     */
    String mdcKey() default "";

    /**
     * Names of the parameters of the defined type to be mapped to the given MDC key.
     *
     * @return The parameter names
     */
    String[] paramNames() default {};

    /**
     * Type of fields to be mapped.
     */
    enum LogMappingType {

        /**
         * Represents a query parameter from a request.
         */
        QUERY,

        /**
         * Represents a path parameter from a request.
         */
        PATH,

        /**
         * Represents a header from a request.
         */
        HEADER

    }

}
