package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Maps parameters of the requests of a resource - query or path parameters, headers - to MDC entries, and
 * activates the logging of its requests as {@link Logged} does.
 * <p>
 * The mappings of every declaration site apply (see {@link LoggedUtils#declarationSites}), but a parameter is
 * mapped once at most, by the mapping naming it on the most specific site, the first one declared there. An
 * automatic mapping leaves out the parameters the others name.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
@Repeatable(LoggedMappings.class)
public @interface LoggedMapping {

    /**
     * Type of the parameters mapped.
     *
     * @return The type of the parameters
     */
    MappingType type();

    /**
     * Maps every parameter of the type under its own name, after {@link #mdcPrefix()}.
     * <p>
     * The client chooses these names, so an automatic mapping only adds entries: a parameter whose key is
     * taken - by a {@link LoggedField}, an explicit mapping or an entry already in MDC - is left out, as is one
     * without a name. Set a prefix all the same for an untrusted client, which could otherwise choose the key
     * of an entry the application puts later on.
     *
     * @return {@code true} to map every parameter, {@code false} to map those named
     */
    boolean auto() default false;

    /**
     * Prefix of the MDC keys, keeping the names a client chooses (see {@link #auto()}) apart from the keys of
     * the application.
     *
     * @return The prefix
     */
    String mdcPrefix() default "";

    /**
     * MDC key of the first named parameter the request carries, after {@link #mdcPrefix()}. Left empty, the
     * named parameters are mapped by no mapping at all, automatic ones included.
     *
     * @return The MDC key, empty to exclude the named parameters
     */
    String mdcKey() default "";

    /**
     * Names of the parameters mapped to {@link #mdcKey()}.
     *
     * @return The parameter names
     */
    String[] paramNames() default {};

    /**
     * Type of the parameters of a request a mapping reads.
     */
    enum MappingType {

        /**
         * Query parameter.
         */
        QUERY,

        /**
         * Path parameter.
         */
        PATH,

        /**
         * Header.
         */
        HEADER

    }

}
