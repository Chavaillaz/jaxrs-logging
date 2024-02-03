package com.chavaillaz.jakarta.rs;

/**
 * Functional interface to filter the content of a request and response body.
 */
@FunctionalInterface
public interface LoggedBodyFilter {

    /**
     * Filters the given body to update or delete possible sensitive elements.
     *
     * @param body The body content
     * @return The filtered body content
     */
    String filterBody(String body);

}
