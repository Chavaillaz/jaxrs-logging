package com.chavaillaz.jakarta.rs.internal;

import static java.lang.Character.isHighSurrogate;
import static java.util.UUID.randomUUID;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.rs.LoggedFeature;

/**
 * What a value coming from outside the application goes through before this library logs it: its control
 * characters are replaced, so it cannot forge a log line, and an identifier is bounded, and never blank.
 */
public final class Sanitizer {

    /**
     * Maximum length kept from a client-supplied {@value LoggedFeature#REQUEST_ID_HEADER} header before it
     * is stored in MDC.
     * <p>
     * A container's overall header size limit is shared across every header of the request, not applied
     * individually, so without a limit of its own a client can inflate every single log line written
     * during the request by supplying an excessively long identifier.
     */
    public static final int REQUEST_ID_MAX_LENGTH = 128;

    /**
     * Pattern matching control characters (e.g. CR, LF) that must be removed from client-controlled
     * input (headers, query or path parameters) before it is stored in MDC, to prevent an attacker
     * from forging fake log entries or corrupting the log line (log injection).
     * <p>
     * Covers every control character Unicode defines, and not only the ASCII ones {@code \p{Cntrl}} stands
     * for: the C1 set is what the bytes {@code 0x80} to {@code 0x9F} of an ISO-8859-1 header decode to, and
     * holds {@code U+0085}, a line break to some log viewers, and {@code U+009B}, which terminals read as the
     * start of an escape sequence. Also covers {@code U+2028}/{@code U+2029}, which several log viewers and
     * JavaScript-based log pipelines treat as line breaks.
     */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");

    private Sanitizer() {
        // Utility class
    }

    /**
     * Replaces the control characters (e.g. CR, LF) of the given value with spaces, see
     * {@link #CONTROL_CHARACTERS}.
     *
     * @param value The value to sanitize
     * @return The sanitized value, or {@code null} if the given value was {@code null}
     */
    public static @Nullable String sanitize(@Nullable String value) {
        return value == null ? null : CONTROL_CHARACTERS.matcher(value).replaceAll(" ");
    }

    /**
     * Gets the identifier to log a request under from the one obtained for it - read from its
     * {@value LoggedFeature#REQUEST_ID_HEADER} header by default - sanitized and truncated to
     * {@link #REQUEST_ID_MAX_LENGTH} characters, or a random UUID when none was obtained.
     *
     * @param obtained The identifier obtained for the request, {@code null} if there is none
     * @return The request identifier, never blank
     */
    public static String requestIdOf(@Nullable String obtained) {
        // Sanitized before being checked, as sanitizing turns an identifier made of nothing but control
        // characters blank, and a blank value is never put in MDC
        String requestId = sanitize(obtained);
        return isNotBlank(requestId) ? truncate(requestId) : randomUUID().toString();
    }

    /**
     * Truncates the given request identifier to {@link #REQUEST_ID_MAX_LENGTH} characters, leaving out
     * whole a character the limit would otherwise cut in half: a character outside the Basic Multilingual
     * Plane takes two {@code char}s, and keeping only the first of them leaves a string that is no longer
     * valid text, which an appender encoding it then writes as a replacement character, or rejects.
     *
     * @param requestId The request identifier to truncate
     * @return The identifier, truncated if it was longer than allowed
     */
    private static String truncate(String requestId) {
        if (requestId.length() <= REQUEST_ID_MAX_LENGTH) {
            return requestId;
        }
        int end = isHighSurrogate(requestId.charAt(REQUEST_ID_MAX_LENGTH - 1))
                ? REQUEST_ID_MAX_LENGTH - 1
                : REQUEST_ID_MAX_LENGTH;
        return requestId.substring(0, end);
    }

}
