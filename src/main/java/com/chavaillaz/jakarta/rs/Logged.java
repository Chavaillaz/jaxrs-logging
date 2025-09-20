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
     * Body logging configuration for requests and/or responses.
     *
     * @return The request logging configuration
     */
    LoggedBody[] value() default {};

}
