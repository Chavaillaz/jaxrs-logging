package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Annotation of an application asking {@link UserLoggingConfiguration} to describe the requests of a resource
 * with user specific data.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface UserLogged {

    /**
     * Indicates whether the user agent is put in MDC while the request is processed and logged.
     *
     * @return {@code true} to log the user agent, {@code false} otherwise
     */
    boolean userAgent() default false;

}
