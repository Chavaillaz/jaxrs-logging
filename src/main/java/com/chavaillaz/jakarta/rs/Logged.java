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
     * Indicates if the request body must be as MDC field when logging the request.
     *
     * @return {@code true} to log the request body, {@code false} otherwise
     */
    boolean requestBody() default false;

    /**
     * Indicates if the response body must be as MDC field when logging the request.
     *
     * @return {@code true} to log the response body, {@code false} otherwise
     */
    boolean responseBody() default false;

}
