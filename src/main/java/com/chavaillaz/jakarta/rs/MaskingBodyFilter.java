package com.chavaillaz.jakarta.rs;

import static java.util.Objects.requireNonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base for the body filters replacing part of a body matched by a regular expression, so a value that
 * must not reach the logs is masked while the rest of the payload stays readable.
 * <p>
 * Ready-made subclasses cover the two formats this is almost always needed for,
 * {@link JsonMaskingBodyFilter} and {@link FormMaskingBodyFilter}, with
 * {@link RegexMaskingBodyFilter} left for anything else. As {@link LoggedBody#filters()} takes classes
 * that must be instantiable without arguments, configuring one for a resource means declaring a subclass
 * fixing its configuration:
 * <pre>{@code
 * public class CredentialsMask extends JsonMaskingBodyFilter {
 *
 *     public CredentialsMask() {
 *         super("password", "token");
 *     }
 *
 * }
 * }</pre>
 * {@link LoggedClientFilter.Builder#bodyFilters(LoggedBodyFilter...)} takes instances directly, so no
 * subclass is needed there.
 * <p>
 * Masking is deliberately done on the captured text rather than on a parsed representation of it: the
 * body reaching a filter may have been truncated by {@link LoggedBody#limit()}, or be malformed - which
 * is precisely when the logs matter most - and a parser would reject both. The trade-off is that
 * matching is structural only as far as a regular expression can be, which each subclass documents.
 */
public abstract class MaskingBodyFilter implements LoggedBodyFilter {

    /**
     * Replacement used when none is given, short and unmistakable for a real value.
     */
    public static final String DEFAULT_MASK = "***";

    protected final Pattern pattern;
    protected final int group;
    protected final String mask;

    /**
     * Creates a filter masking the given group of every match of the given pattern.
     * <p>
     * A group the pattern does not have is rejected here: accepted, it failed on the first match of every
     * body filtered, and a filter that throws has the body dropped, so every payload the filter was declared
     * for ended up out of the logs, one reported error at a time.
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
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            // Leaves the body untouched, and allocates nothing, for the common case of a payload
            // carrying none of the values this filter masks
            return;
        }

        StringBuilder masked = new StringBuilder(body.length());
        do {
            matcher.appendReplacement(masked, replacement(matcher));
        } while (matcher.find());
        matcher.appendTail(masked);

        body.setLength(0);
        body.append(masked);
    }

    /**
     * Builds the text replacing one whole match, that is the match with its {@link #group} replaced by
     * {@link #mask}, so what surrounds the value (a JSON property name, a parameter name, ...) is kept.
     *
     * @param matcher The matcher positioned on the match to replace
     * @return The replacement, already escaped for {@link Matcher#appendReplacement}
     */
    protected String replacement(Matcher matcher) {
        String match = matcher.group();
        if (matcher.start(group) < 0) {
            // Optional group that did not take part in this match: nothing to mask
            return Matcher.quoteReplacement(match);
        }
        int start = matcher.start(group) - matcher.start();
        int end = matcher.end(group) - matcher.start();
        return Matcher.quoteReplacement(match.substring(0, start) + mask + match.substring(end));
    }

}
