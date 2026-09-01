package com.chavaillaz.jakarta.rs;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static jakarta.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM_TYPE;
import static jakarta.ws.rs.core.MediaType.APPLICATION_XML_TYPE;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Set;

import jakarta.ws.rs.core.MediaType;
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

        // Then: the dangling half character is gone, and what is left is marked as incomplete rather
        // than reading, in the logs, as the whole body the application actually received
        assertEquals("Caf" + BoundedLoggedBodyCapture.TRUNCATION_MARKER, result);
    }

    @Test
    @DisplayName("Check content of a body exactly as long as the limit is not reported as truncated")
    void checkContentAtExactlyTheLimitIsNotMarkedTruncated() throws IOException {
        // Given: 5 bytes captured through a sink limited to exactly 5 bytes, so nothing was dropped
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(5);
        capture.sink().write("Hello".getBytes(UTF_8));

        // When
        String result = capture.content(Set.of());

        // Then
        assertEquals("Hello", result);
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

    @Test
    @DisplayName("Check content without a media type still decodes as UTF-8 text")
    void checkContentWithoutMediaTypeDefaultsToText() throws IOException {
        // Given
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write("Café".getBytes(UTF_8));

        // When
        String result = capture.content(Set.of(), null);

        // Then
        assertEquals("Café", result);
    }

    @Test
    @DisplayName("Check content renders a binary media type as hexadecimal instead of decoding it")
    void checkContentRendersBinaryAsHex() throws IOException {
        // Given
        byte[] bytes = {0x00, 0x01, (byte) 0xFF, (byte) 0xCA, (byte) 0xFE};
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write(bytes);

        // When
        String result = capture.content(Set.of(), APPLICATION_OCTET_STREAM_TYPE);

        // Then
        assertEquals("0001ffcafe", result);
    }

    @Test
    @DisplayName("Check content applies filters to the hexadecimal rendering of a binary body")
    void checkContentAppliesFiltersToHexRendering() throws IOException {
        // Given
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write(new byte[]{(byte) 0xAB, (byte) 0xCD});
        LoggedBodyFilter upperCaseFilter = body -> {
            String upper = body.toString().toUpperCase();
            body.setLength(0);
            body.append(upper);
        };

        // When
        String result = capture.content(Set.of(upperCaseFilter), APPLICATION_OCTET_STREAM_TYPE);

        // Then
        assertEquals("ABCD", result);
    }

    @Test
    @DisplayName("Check text-based media types (text/*, JSON, XML) are not treated as binary")
    void checkTextMediaTypesAreNotBinary() {
        assertFalse(BoundedLoggedBodyCapture.isBinary(TEXT_PLAIN_TYPE));
        assertFalse(BoundedLoggedBodyCapture.isBinary(APPLICATION_JSON_TYPE));
        assertFalse(BoundedLoggedBodyCapture.isBinary(APPLICATION_XML_TYPE));
        assertFalse(BoundedLoggedBodyCapture.isBinary(new MediaType("application", "hal+json")));
        assertFalse(BoundedLoggedBodyCapture.isBinary(new MediaType("application", "x-www-form-urlencoded")));
        assertFalse(BoundedLoggedBodyCapture.isBinary(null));
    }

    @Test
    @DisplayName("Check non-text media types (octet-stream, images, multipart) are treated as binary")
    void checkNonTextMediaTypesAreBinary() {
        assertTrue(BoundedLoggedBodyCapture.isBinary(APPLICATION_OCTET_STREAM_TYPE));
        assertTrue(BoundedLoggedBodyCapture.isBinary(new MediaType("application", "pdf")));
        assertTrue(BoundedLoggedBodyCapture.isBinary(new MediaType("image", "png")));
        assertTrue(BoundedLoggedBodyCapture.isBinary(new MediaType("multipart", "form-data")));
    }

    @Test
    @DisplayName("Check a filter that throws drops the body instead of leaking it unfiltered")
    void checkFailingFilterDropsBody() throws IOException {
        // A filter is a "this must never reach the logs" instruction, so a filter that threw halfway
        // through must not result in the raw payload being written: what it was redacting is precisely
        // what must not appear
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write("{\"password\":\"hunter2\"}".getBytes(UTF_8));

        // When
        String result = capture.content(Set.of(body -> {
            throw new IllegalStateException("Filter bug");
        }));

        // Then
        assertEquals(BoundedLoggedBodyCapture.FILTERING_FAILURE_MARKER, result);
        assertFalse(result.contains("hunter2"));
    }

    @Test
    @DisplayName("Check a filter that throws does not propagate into the entity stream being captured")
    void checkFailingFilterDoesNotPropagate() throws IOException {
        // Given
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write("content".getBytes(UTF_8));

        // When / Then: assertDoesNotThrow, as a broken filter breaking the request it was only meant to
        // be logging is the one outcome this must never have
        assertDoesNotThrow(() -> capture.content(Set.of(body -> {
            throw new IllegalStateException("Filter bug");
        }), TEXT_PLAIN_TYPE));
    }

    @Test
    @DisplayName("Check content(Set) without a media type keeps the historical always-UTF-8 behavior")
    void checkSingleArgContentIgnoresMediaType() throws IOException {
        // Given
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write("Café".getBytes(UTF_8));

        // When
        String result = capture.content(Set.of());

        // Then
        assertEquals("Café", result);
    }

}
