package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.internal.Sanitizer.requestIdOf;
import static com.chavaillaz.jakarta.rs.internal.Sanitizer.sanitize;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises {@link Sanitizer} directly: the truncation of a request identifier is covered through a request
 * by {@code LoggedFilterTest}.
 */
@DisplayName("Sanitizer")
class SanitizerTest {

    @Test
    @DisplayName("Check a request identifier the client supplies is kept, sanitized")
    void checkRequestIdKept() {
        assertEquals("abc-123", requestIdOf("abc-123"));
        assertEquals("abc  FAKE LINE", requestIdOf("abc\r\nFAKE LINE"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "\r\n"})
    @DisplayName("Check a request without a usable identifier gets a random one")
    void checkRequestIdGenerated(String header) {
        String requestId = requestIdOf(header);

        assertDoesNotThrow(() -> UUID.fromString(requestId));
        assertNotEquals(requestId, requestIdOf(header));
    }

    @Test
    @DisplayName("Check sanitizing replaces every kind of line break, and leaves null alone")
    void checkSanitize() {
        assertEquals("a b c d e", sanitize("a\nb\u0085c d e"));
        assertNull(sanitize(null));
    }

    @Test
    @DisplayName("Check sanitizing replaces the control characters outside ASCII, and only those")
    void checkSanitizeC1() {
        // CSI starts a terminal escape sequence the way ESC [ does, and PAD opens the C1 set; both are what
        // an ISO-8859-1 header decodes the bytes 0x9B and 0x80 to
        assertEquals("a 31mb c", sanitize("a\u009B31mb\u0080c"));
        assertEquals("café à 5 €", sanitize("café à 5 €"));
    }

}
