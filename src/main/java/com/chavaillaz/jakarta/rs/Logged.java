package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Activates the logging of the requests received by a JAX-RS resource, through {@link LoggedFilter} (and
 * {@link LoggedBodyInterceptor} for their bodies), to which JAX-RS binds it.
 * <p>
 * Put on a resource class, it applies to every resource method of it; put on a resource method, to that
 * method alone. Its content, the {@link LoggedBody} configurations, is taken from the most specific
 * declaration found (see {@link LoggedUtils#declarationSites}), entirely: a method redeclaring a bare
 * {@code @Logged} opts out of the body logging its class configures.
 * <p>
 * It is also what activates the other annotations of this library: a {@link LoggedBody} or a
 * {@link LoggedMapping} configures the logging this annotation activates, and does nothing on a resource
 * that is not {@code @Logged} at its method or class level. Repeating {@link LoggedBody} is the one
 * exception, as the compiler wraps the repeated annotations into this one.
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
