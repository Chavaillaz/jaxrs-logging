package com.chavaillaz.jakarta.rs;

import static java.util.stream.Collectors.joining;

import java.util.Arrays;
import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Masks the value of the named parameters of an {@code application/x-www-form-urlencoded} body, keeping
 * the rest of the payload readable.
 * <pre>{@code
 * new FormMaskingBodyFilter("password", "client_secret");
 * // grant_type=password&username=jane&password=hunter2
 * // -> grant_type=password&username=jane&password=***
 * }</pre>
 * Only a value is ever masked, never a parameter name that happens to equal one: in the example above,
 * {@code grant_type=password} is left alone, which matters because an OAuth token request carries both.
 * <p>
 * Parameter names are matched exactly and in their encoded form, which is how they appear in the body.
 * Empty values are matched too, so a parameter sent with no value still shows as masked rather than
 * silently revealing that it was empty.
 */
public class FormMaskingBodyFilter extends MaskingBodyFilter {

    /**
     * Creates a filter masking the given parameters with {@link #DEFAULT_MASK}.
     *
     * @param parameters The names of the form parameters whose value must be masked
     */
    public FormMaskingBodyFilter(String... parameters) {
        this(DEFAULT_MASK, Arrays.asList(parameters));
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
        // The first group anchors the name to the start of the body or to a separator, so a parameter
        // name is only recognized where one can actually begin, and is kept as-is by the replacement
        return Pattern.compile("(\\A|[&?])(?:" + names + ")=([^&]*)");
    }

}
