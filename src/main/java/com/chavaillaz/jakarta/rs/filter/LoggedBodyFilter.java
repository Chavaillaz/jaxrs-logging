package com.chavaillaz.jakarta.rs.filter;

/**
 * Rewrites a captured body before it is logged, typically to mask what must not reach the logs. Shared by
 * every request, an implementation must be stateless and thread-safe.
 * <p>
 * Implementing {@link #filter(StringBuilder)} is enough. A filter with nothing to change in most bodies can also
 * override {@link #apply(CharSequence)} to hand such a body back as it is, sparing a copy of it (see
 * {@link MaskingBodyFilter}).
 */
@FunctionalInterface
public interface LoggedBodyFilter {

    /**
     * Filters the given body, masking or removing what must not be logged.
     *
     * @param body The body content
     */
    void filter(StringBuilder body);

    /**
     * Filters the given body without modifying it, as the captures call it: copies it into a
     * {@link StringBuilder} for {@link #filter(StringBuilder)} by default. The result is only read, so it may
     * be the very sequence given.
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
