package com.chavaillaz.jakarta.rs;

/**
 * Functional interface to filter the content of a request and response body.
 * Note that its implementations must be stateless and thread-safe.
 */
@FunctionalInterface
public interface LoggedBodyFilter {

    /**
     * Filters the given body to update or delete possible sensitive elements.
     *
     * @param body The body content
     */
    void filter(StringBuilder body);

}
