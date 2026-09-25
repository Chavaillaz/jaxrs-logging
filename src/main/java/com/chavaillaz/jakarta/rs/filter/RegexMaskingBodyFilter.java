package com.chavaillaz.jakarta.rs.filter;

import java.util.regex.Pattern;

/**
 * Masks whatever a given regular expression captures, for a body format the other built-in filters do
 * not cover.
 * <p>
 * The pattern is expected to match a value together with enough of its surroundings to identify it, and
 * to capture the part to hide in one group:
 * <pre>{@code
 * // A bearer token appearing inside a body, keeping the scheme visible
 * new RegexMaskingBodyFilter("(?i)Bearer (\\S+)", 1);
 *
 * // Every IBAN of a payment payload
 * new RegexMaskingBodyFilter("\\b([A-Z]{2}\\d{2}[A-Z0-9]{10,30})\\b", 1);
 * }</pre>
 * Anything outside the captured group is left as it is, so the logs still show what was masked and where.
 *
 * @see JsonMaskingBodyFilter
 * @see FormMaskingBodyFilter
 */
public class RegexMaskingBodyFilter extends MaskingBodyFilter {

    /**
     * Creates a filter replacing the given group of the given pattern with {@link #DEFAULT_MASK}.
     *
     * @param pattern The pattern matching the values to mask
     * @param group   The number of the capturing group to replace within each match
     */
    public RegexMaskingBodyFilter(String pattern, int group) {
        this(Pattern.compile(pattern), group, DEFAULT_MASK);
    }

    /**
     * Creates a filter replacing the given group of the given pattern.
     *
     * @param pattern The pattern matching the values to mask
     * @param group   The number of the capturing group to replace within each match
     * @param mask    The replacement to write in place of the matched group
     */
    public RegexMaskingBodyFilter(Pattern pattern, int group, String mask) {
        super(pattern, group, mask);
    }

}
