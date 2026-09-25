package com.chavaillaz.jakarta.rs.filter;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

import java.util.Collection;
import java.util.regex.Pattern;

import com.chavaillaz.jakarta.rs.LoggedBody;

/**
 * Masks the value of the named properties of a JSON body, keeping the rest of the payload readable.
 * <pre>{@code
 * new JsonMaskingBodyFilter("password", "token");
 * // {"user":"jane","password":"hunter2"} -> {"user":"jane","password":"***"}
 * }</pre>
 * Property names are matched exactly, as JSON names are case-sensitive, and at any depth of the
 * document: a filter is a blanket "never log this" instruction, and a value that must not be logged at
 * the top level must not be logged nested in an array of objects either.
 * <p>
 * Scalar values are masked whatever their type, so a masked property never leaks through by being sent
 * as a number or a boolean rather than a string, and the result stays valid JSON: the replacement is
 * always a JSON string. A property whose value is an object or an array is <em>not</em> masked, as
 * matching balanced braces is beyond what a regular expression can do reliably (see
 * {@link MaskingBodyFilter} for why the body is not parsed); mask the scalar properties inside it
 * instead, or use {@link LoggedBody#limit()} to keep it out of the logs altogether.
 * <p>
 * A string value cut short by {@link LoggedBody#limit()} is masked too, although it lacks the closing
 * quote a complete string ends with, so no part of a secret straddling the limit is logged in the clear.
 */
public class JsonMaskingBodyFilter extends MaskingBodyFilter {

    /**
     * Matches a JSON scalar: a string with its escapes, a number in any of the forms the grammar allows,
     * or one of the three literals.
     * <p>
     * A string is matched as runs of plain characters separated by escapes, rather than as a repetition
     * of "one plain character or one escape": {@link java.util.regex} recurses once per repetition of a
     * group like the latter, which overflows the stack on a value a few thousand characters long - a JWT
     * carrying a handful of claims, for instance. A run of plain characters is matched iteratively, which
     * leaves one level of recursion per escape sequence rather than per character.
     * <p>
     * A string also ends at the very end of the body, possibly right after the backslash of an escape
     * sequence cut in half, so a value truncated by the body limit is masked as well (see the class
     * documentation).
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
        // The mask is written as a JSON string, so masking a numeric or boolean property leaves a
        // document that still parses
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
        return Pattern.compile("\"(?:" + names + ")\"\\s*:\\s*(" + SCALAR + ")", Pattern.DOTALL);
    }

}
