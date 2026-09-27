package com.chavaillaz.jakarta.rs.filter;

import static java.util.Objects.requireNonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chavaillaz.jakarta.rs.LoggedBody;
import com.chavaillaz.jakarta.rs.client.LoggedClientFeature;

/**
 * Base of the body filters masking what a regular expression matches, keeping the rest of the body readable:
 * {@link JsonMaskingBodyFilter} and {@link FormMaskingBodyFilter}, and {@link RegexMaskingBodyFilter} for any
 * other format. As {@link LoggedBody#filters()} takes classes instantiable without arguments, a resource names
 * a subclass fixing the configuration:
 * <pre>{@code
 * public class CredentialsMask extends JsonMaskingBodyFilter {
 *
 *     public CredentialsMask() {
 *         super("password", "token");
 *     }
 *
 * }
 * }</pre>
 * {@link LoggedClientFeature.Builder#bodyFilters(LoggedBodyFilter...)} takes instances directly.
 * <p>
 * Masking works on the captured text rather than a parsed document, as a body cut by {@link LoggedBody#limit()},
 * or malformed, is one a parser rejects: matching is structural only as far as a regular expression is.
 */
public abstract class MaskingBodyFilter implements LoggedBodyFilter {

    /**
     * Replacement used when none is given, short and unmistakable for a real value.
     */
    public static final String DEFAULT_MASK = "***";

    /**
     * Pattern matching the values to mask, along with enough of their surroundings to identify them.
     */
    protected final Pattern pattern;

    /**
     * Number of the capturing group of {@link #pattern} replaced within each match.
     */
    protected final int group;

    /**
     * Replacement written in place of each value masked.
     */
    protected final String mask;

    /**
     * Creates a filter masking the given group of every match of the given pattern, rejecting a group the
     * pattern does not have rather than dropping every body it filters.
     *
     * @param pattern The pattern matching the values to mask
     * @param group   The number of the capturing group to replace within each match
     * @param mask    The replacement to write in place of the matched group
     * @throws IllegalArgumentException if the pattern has no capturing group of the given number
     */
    protected MaskingBodyFilter(Pattern pattern, int group, String mask) {
        int groupCount = pattern.matcher("").groupCount();
        if (group < 0 || group > groupCount) {
            throw new IllegalArgumentException("Group " + group + " does not exist in the pattern " + pattern
                    + ", which has " + groupCount + " capturing group(s)");
        }
        this.pattern = pattern;
        this.group = group;
        this.mask = requireNonNull(mask, "A mask is required");
    }

    @Override
    public void filter(StringBuilder body) {
        CharSequence masked = apply(body);
        if (masked != body) {
            body.setLength(0);
            body.append(masked);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Hands the body back as it is when nothing matches, and builds the masked body in one pass otherwise.
     */
    @Override
    public CharSequence apply(CharSequence body) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return body;
        }

        StringBuilder masked = new StringBuilder(body.length());
        int copied = 0;
        do {
            // An optional group that did not take part in this match has nothing to mask: the match is then
            // copied along with the text following it
            if (matcher.start(group) >= 0) {
                masked.append(body, copied, matcher.start(group)).append(mask);
                copied = matcher.end(group);
            }
        } while (matcher.find());
        return masked.append(body, copied, body.length());
    }

}
