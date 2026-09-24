package com.chavaillaz.jakarta.rs;

/**
 * Functional interface to filter the content of a request and response body.
 * Note that its implementations must be stateless and thread-safe.
 * <p>
 * Implementing {@link #filter(StringBuilder)} is enough. A filter that often has nothing to change in a body
 * - one masking a value most payloads do not carry, typically - can also override {@link #apply(CharSequence)}
 * to hand the body back as it is in that case, which spares a copy of the whole body per filter and per
 * request (see {@link MaskingBodyFilter}).
 */
@FunctionalInterface
public interface LoggedBodyFilter {

    /**
     * Filters the given body to update or delete possible sensitive elements.
     *
     * @param body The body content
     */
    void filter(StringBuilder body);

    /**
     * Filters the given body without modifying it, returning the filtered content.
     * <p>
     * This is what the body captures call. By default, it copies the body into a {@link StringBuilder}
     * given to {@link #filter(StringBuilder)}, which a filter changing nothing in most bodies can avoid by
     * overriding this method to return the body itself when it has nothing to change - the result is only
     * read, never modified, so returning the very sequence given is safe.
     *
     * @param body The body content, which must not be modified
     * @return The filtered body, possibly the very sequence given if nothing was filtered
     */
    default CharSequence apply(CharSequence body) {
        StringBuilder builder = new StringBuilder(body);
        filter(builder);
        return builder;
    }

}
