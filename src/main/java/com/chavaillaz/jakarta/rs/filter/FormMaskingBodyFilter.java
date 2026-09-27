package com.chavaillaz.jakarta.rs.filter;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Masks the value of the named parameters of an {@code application/x-www-form-urlencoded} body.
 * <pre>{@code
 * new FormMaskingBodyFilter("password", "client_secret");
 * // grant_type=password&username=jane&password=hunter2
 * // -> grant_type=password&username=jane&password=***
 * }</pre>
 * A value equal to a masked name is left alone, as {@code grant_type=password} is above. Names are matched
 * exactly, encoded as the body carries them, and an empty value is masked too.
 */
public class FormMaskingBodyFilter extends MaskingBodyFilter {

    /**
     * Creates a filter masking the given parameters with {@link #DEFAULT_MASK}.
     *
     * @param parameters The names of the form parameters whose value must be masked
     */
    public FormMaskingBodyFilter(String... parameters) {
        this(DEFAULT_MASK, asList(parameters));
    }

    /**
     * Creates a filter masking the given parameters.
     *
     * @param mask       The replacement to write in place of each masked value
     * @param parameters The names of the form parameters whose value must be masked
     */
    public FormMaskingBodyFilter(String mask, Collection<String> parameters) {
        super(pattern(parameters), 2, mask);
    }

    /**
     * Builds the pattern matching the value of any of the given parameters.
     *
     * @param parameters The names of the form parameters whose value must be masked
     * @return The pattern, capturing the value to mask as its second group
     */
    protected static Pattern pattern(Collection<String> parameters) {
        if (parameters.isEmpty()) {
            throw new IllegalArgumentException("At least one parameter name is required to mask anything");
        }
        String names = parameters.stream()
                .map(Pattern::quote)
                .collect(joining("|"));
        // The first group anchors the name where a parameter begins, and is kept by the replacement
        return Pattern.compile("(\\A|[&?])(?:" + names + ")=([^&]*)");
    }

}
