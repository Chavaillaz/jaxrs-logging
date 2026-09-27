package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Activates the logging of the requests of a JAX-RS resource by {@link LoggedFeature}, and holds the
 * {@link LoggedBody} configurations of their bodies.
 * <p>
 * Declared on a resource class, it applies to every resource method of it; on a resource method, to that
 * method alone - and so on the interfaces and superclasses of the class, and on the methods of theirs a
 * resource method implements or overrides (see {@link LoggedUtils#declarationSites}). The most specific
 * declaration wins entirely: a method redeclaring a bare {@code @Logged} opts out of the body logging of its
 * class.
 * <p>
 * {@link LoggedBody} and {@link LoggedMapping} activate the logging as well. None of them is a JAX-RS name
 * binding: {@link LoggedFeature} reads them wherever they are declared.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface Logged {

    /**
     * Body logging configurations, none logging no body.
     *
     * @return The body logging configurations
     */
    LoggedBody[] value() default {};

}
