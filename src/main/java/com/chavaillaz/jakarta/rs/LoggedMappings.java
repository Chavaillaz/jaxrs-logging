package com.chavaillaz.jakarta.rs;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Container of repeated {@link LoggedMapping} annotations, which the compiler declares in their place.
 */
@Documented
@Retention(RUNTIME)
@Target({TYPE, METHOD})
public @interface LoggedMappings {

    /**
     * The mappings.
     *
     * @return The mappings
     */
    LoggedMapping[] value() default {};

}
