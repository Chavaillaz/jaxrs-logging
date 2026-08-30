package com.chavaillaz.jakarta.rs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bounded logged body capture")
class BoundedLoggedBodyCaptureTest {

    @Test
    @DisplayName("Check content decodes captured bytes as UTF-8 regardless of the platform default charset")
    void checkContentUsesUtf8() throws IOException {
        // Given
        String text = "Café ☕ résumé";
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write(text.getBytes(UTF_8));

        // When
        String result = capture.content(Set.of());

        // Then
        assertEquals(text, result);
    }

    @Test
    @DisplayName("Check content applies every given filter")
    void checkContentAppliesFilters() throws IOException {
        // Given
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write("{\"secret-code\": \"1234-ABCD\"}".getBytes(UTF_8));

        // When
        String result = capture.content(Set.of(new SensitiveBodyFilter()));

        // Then
        assertEquals("{\"secret-code\": \"masked\"}", result);
    }

    @Test
    @DisplayName("Check content trims a dangling incomplete UTF-8 character left by the configured limit")
    void checkContentTrimsTruncatedCharacter() throws IOException {
        // Given: "Café" (5 bytes) captured through a sink limited to 4 bytes, cutting right
        // after the lead byte of the trailing 2-byte character (é)
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(4);
        capture.sink().write("Café".getBytes(UTF_8));

        // When
        String result = capture.content(Set.of());

        // Then
        assertEquals("Caf", result);
    }

    @Test
    @DisplayName("Check content leaves complete content untouched when the limit was not reached")
    void checkContentLeavesUntruncatedContentUntouched() throws IOException {
        // Given
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(100);
        capture.sink().write("Café".getBytes(UTF_8));

        // When
        String result = capture.content(Set.of());

        // Then
        assertEquals("Café", result);
        assertFalse(((BoundedOutputStream) capture.sink()).isTruncated());
    }

}
