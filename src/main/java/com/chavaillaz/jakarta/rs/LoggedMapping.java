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
 * <p>
 * Only takes effect on a resource {@link Logged} activates at its method or class level: a mapping on a
 * resource that is not {@code @Logged} activates nothing by itself.
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
    MappingType type();

    /**
     * Flag indicating if the mapping must be done automatically
     * (one to one, without changing the names of parameters) for the given type.
     * <p>
     * As the resulting MDC key is derived from the parameter or header name, which is controlled by
     * the client sending the request, always set a non-empty {@link #mdcPrefix()} when enabling this
     * for an untrusted source. Without it, a client could choose a parameter name that collides with
     * another MDC entry. Note that a collision with a field reserved by {@link LoggedFilter} itself
     * (e.g. {@code request-id}, {@code duration}) is always ignored, regardless of the prefix used.
     *
     * @return {@code true} to automatically map the parameters, {@code false} otherwise
     */
    boolean auto() default false;

    /**
     * Prefix to be added to the MDC key.
     * <p>
     * Effectively mandatory when using {@link #auto()} on client-controlled input (headers, query or
     * path parameters), as it is otherwise the only protection against a client choosing a parameter
     * name that collides with another MDC entry.
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
    enum MappingType {

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
