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
     * Maximum length kept of an identifier a client supplies in the {@value LoggedFeature#REQUEST_ID_HEADER}
     * header, which would otherwise inflate every line of its request.
     */
    public static final int REQUEST_ID_MAX_LENGTH = 128;

    /**
     * Control characters replaced in what a client controls before it is logged, so it cannot forge a line: every
     * one Unicode defines - the C1 set holds {@code U+0085}, a line break to some viewers, and {@code U+009B},
     * which terminals read as the start of an escape sequence - and {@code U+2028} and {@code U+2029}, which
     * some viewers treat as line breaks.
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
        // Sanitized before being checked, which turns an identifier of control characters blank
        String requestId = sanitize(obtained);
        return isNotBlank(requestId) ? truncate(requestId) : randomUUID().toString();
    }

    /**
     * Truncates the given identifier to {@link #REQUEST_ID_MAX_LENGTH} characters, without cutting a surrogate
     * pair in half, which an appender would encode as a replacement character.
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
