package com.chavaillaz.jakarta.rs.filter;

import static java.util.Arrays.asList;
import static java.util.regex.Pattern.DOTALL;
import static java.util.stream.Collectors.joining;

import java.util.Collection;
import java.util.regex.Pattern;

import com.chavaillaz.jakarta.rs.LoggedBody;

/**
 * Masks the value of the named properties of a JSON body, at any depth, their names matched exactly.
 * <pre>{@code
 * new JsonMaskingBodyFilter("password", "token");
 * // {"user":"jane","password":"hunter2"} -> {"user":"jane","password":"***"}
 * }</pre>
 * A scalar value is masked whatever its type, by a JSON string, so the result stays valid JSON, and so is a
 * string {@link LoggedBody#limit()} cut short. A value that is an object or an array is <em>not</em> masked, as a
 * regular expression cannot match balanced braces: mask the properties inside it.
 */
public class JsonMaskingBodyFilter extends MaskingBodyFilter {

    /**
     * Matches a JSON scalar: a string with its escapes, a number, or a literal. A string is matched as runs of
     * plain characters between escapes, as {@link java.util.regex} recurses once per repetition of a group, which
     * overflows the stack on a long value, and ends at the end of the body too, for a value cut short.
     */
    private static final String SCALAR = "\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*(?:\"|\\\\?\\z)|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|true|false|null";

    /**
     * Creates a filter masking the given properties with {@link #DEFAULT_MASK}.
     *
     * @param properties The names of the JSON properties whose value must be masked
     */
    public JsonMaskingBodyFilter(String... properties) {
        this(DEFAULT_MASK, asList(properties));
    }

    /**
     * Creates a filter masking the given properties.
     *
     * @param mask       The replacement to write in place of each masked value
     * @param properties The names of the JSON properties whose value must be masked
     */
    public JsonMaskingBodyFilter(String mask, Collection<String> properties) {
        // A JSON string, so masking a number or a boolean leaves a document that parses
        super(pattern(properties), 1, "\"" + mask + "\"");
    }

    /**
     * Builds the pattern matching the value of any of the given properties.
     *
     * @param properties The names of the JSON properties whose value must be masked
     * @return The pattern, capturing the value to mask as its first group
     */
    protected static Pattern pattern(Collection<String> properties) {
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("At least one property name is required to mask anything");
        }
        String names = properties.stream()
                .map(Pattern::quote)
                .collect(joining("|"));
        return Pattern.compile("\"(?:" + names + ")\"\\s*:\\s*(" + SCALAR + ")", DOTALL);
    }

}
