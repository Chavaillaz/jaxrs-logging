package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Activates the logging of the requests received by a JAX-RS resource, by {@link LoggedFilter} (and
 * {@link LoggedBodyInterceptor} for their bodies).
 * <p>
 * Put on a resource class, it applies to every resource method of it; put on a resource method, to that
 * method alone - and the same goes for the interfaces and superclasses of the class, and the methods of
 * theirs a resource method implements or overrides (see {@link LoggedUtils#declarationSites}). Its content, the {@link LoggedBody}
 * configurations, is taken from the most specific declaration found, entirely: a method redeclaring a bare
 * {@code @Logged} opts out of the body logging its class configures.
 * <p>
 * The other annotations of this library activate the logging as well, as they configure it: a resource
 * declaring a {@link LoggedBody} or a {@link LoggedMapping}, once or repeated, has its requests logged,
 * {@code @Logged} or not. None of them is a JAX-RS name binding: the providers apply to every resource, and
 * read the annotations themselves wherever they are declared, whatever the JAX-RS implementation makes of
 * those an interface declares.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface Logged {

    /**
     * Body logging configurations for requests and/or responses, none logging no body at all.
     *
     * @return The body logging configurations
     */
    LoggedBody[] value() default {};

}
