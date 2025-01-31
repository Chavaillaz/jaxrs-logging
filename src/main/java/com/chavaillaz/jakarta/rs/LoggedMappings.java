package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Annotation defining a list of mappings from parameters to MDC entries
 * to be used by the {@link LoggedFilter} when logging a request.
 */
@Documented
@NameBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface LoggedMappings {

    /**
     * List defining the mapping from parameters of the given types to MDC entries.
     *
     * @return The list of mappings
     */
    LoggedMapping[] value() default {};

}
