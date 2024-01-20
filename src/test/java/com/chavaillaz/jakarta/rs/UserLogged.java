package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Annotation activating the filter {@link UserLoggedFilter}
 * in order to log incoming requests received by a JAX-RS resource with user specific data.
 */
@Documented
@NameBinding
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface UserLogged {

    /**
     * Base filter logging configuration for {@link LoggedFilter}
     *
     * @return The annotation configuration
     */
    Logged logging() default @Logged();

    /**
     * Indicates if the user agent must be as MDC field when processing and logging the request.
     *
     * @return {@code true} to log the user agent, {@code false} otherwise
     */
    boolean userAgent() default false;

}
