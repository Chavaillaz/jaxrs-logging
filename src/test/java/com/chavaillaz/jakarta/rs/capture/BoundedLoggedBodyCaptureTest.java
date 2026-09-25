package com.chavaillaz.jakarta.rs.capture;

import static com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture.FILTERING_FAILURE_MARKER;
import static com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture.TRUNCATION_MARKER;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.NO_LIMIT;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static jakarta.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM_TYPE;
import static jakarta.ws.rs.core.MediaType.APPLICATION_XML_TYPE;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.util.Set;

import com.chavaillaz.jakarta.rs.SensitiveBodyFilter;
import com.chavaillaz.jakarta.rs.filter.JsonMaskingBodyFilter;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.filter.RegexMaskingBodyFilter;
import com.sun.management.ThreadMXBean;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    @DisplayName("Check an invalid limit is rejected with the message naming what has to be fixed")
    void checkInvalidLimitRejectedWithUsefulMessage() {
        // Sizing the buffer on the limit used to reject it first, with a message about an initial size
        // nobody can trace back to the LoggedBody#limit that actually has to be changed - and, now that
        // a capture failing to be created no longer fails the exchange, that message is all there is
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new BoundedLoggedBodyCapture(-2));

        // Then
        assertTrue(thrown.getMessage().contains("Limit must be -1 (unlimited) or a positive value"));
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
        assertEquals("Caf" + TRUNCATION_MARKER, result);
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
        assertFalse(((CaptureBuffer) capture.sink()).isTruncated());
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
    @DisplayName("Check content decodes a text body with the charset its media type declares")
    void checkContentDecodesDeclaredCharset() throws IOException {
        // Given
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write("Café".getBytes(ISO_8859_1));

        // When
        String result = capture.content(Set.of(), TEXT_PLAIN_TYPE.withCharset("ISO-8859-1"));

        // Then: decoded as UTF-8, the é used to be logged as a replacement character
        assertEquals("Café", result);
    }

    @Test
    @DisplayName("Check content decodes as UTF-8 a body whose declared charset is not supported")
    void checkContentFallsBackToUtf8ForUnsupportedCharset() throws IOException {
        // Given
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write("Café".getBytes(UTF_8));

        // When
        String result = capture.content(Set.of(), TEXT_PLAIN_TYPE.withCharset("no-such-charset"));

        // Then
        assertEquals("Café", result);
    }

    @Test
    @DisplayName("Check content trims a character the limit cut in half in a multi-byte charset other than UTF-8")
    void checkContentTrimsTruncatedCharacterOfDeclaredCharset() throws IOException {
        // Given: "Café" in UTF-16 (8 bytes) captured through a sink limited to 7 bytes, cutting the é in half
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(7);
        capture.sink().write("Café".getBytes(UTF_16BE));

        // When
        String result = capture.content(Set.of(), TEXT_PLAIN_TYPE.withCharset("UTF-16BE"));

        // Then
        assertEquals("Caf" + TRUNCATION_MARKER, result);
    }

    @Test
    @DisplayName("Check content keeps the last character of a truncated body in a single-byte charset")
    void checkContentKeepsLastCharacterOfSingleByteCharset() throws IOException {
        // Given: "Café!" in ISO-8859-1 (5 bytes) captured through a sink limited to 4 bytes, ending with the
        // byte of the é, which read as UTF-8 is the start of a longer sequence the limit would have cut
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(4);
        capture.sink().write("Café!".getBytes(ISO_8859_1));

        // When
        String result = capture.content(Set.of(), TEXT_PLAIN_TYPE.withCharset("ISO-8859-1"));

        // Then: a single-byte charset has no character to cut in half, so the é is complete and kept
        assertEquals("Café" + TRUNCATION_MARKER, result);
    }

    @ParameterizedTest(name = "limit={0}")
    @ValueSource(ints = {2, 3, 4})
    @DisplayName("Check content leaves out a four-byte UTF-8 character the limit cut, wherever it cut it")
    void checkContentTrimsTruncatedFourByteCharacter(int limit) throws IOException {
        // Given: "a😀" in UTF-8 (5 bytes: a, then the emoji on 4 bytes), cut after 1, 2 or 3 of the emoji's bytes
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(limit);
        capture.sink().write("a😀".getBytes(UTF_8));

        // When
        String result = capture.content(Set.of());

        // Then
        assertEquals("a" + TRUNCATION_MARKER, result);
    }

    @Test
    @DisplayName("Check content keeps a byte that is not UTF-8 at the cut, replaced as it would be anywhere else")
    void checkContentKeepsMalformedByteAtTheCut() throws IOException {
        // Given: a byte that cannot start a UTF-8 character, right where the limit cut
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(2);
        capture.sink().write(new byte[]{'a', (byte) 0xFF, 'b'});

        // When
        String result = capture.content(Set.of());

        // Then: malformed rather than cut in half, it is decoded the way the rest of the body is
        assertEquals("a�" + TRUNCATION_MARKER, result);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"windows-1252, Café!, 4, Café", "Shift_JIS, 日本語, 3, 日", "UTF-16LE, Café, 7, Caf"})
    @DisplayName("Check content leaves out a character the limit cut in any other charset, and only that one")
    void checkContentTrimsTruncatedCharacterOfOtherCharsets(String charset, String text, int limit, String expected) throws IOException {
        // Given
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(limit);
        capture.sink().write(text.getBytes(Charset.forName(charset)));

        // When
        String result = capture.content(Set.of(), TEXT_PLAIN_TYPE.withCharset(charset));

        // Then
        assertEquals(expected + TRUNCATION_MARKER, result);
    }

    @Test
    @DisplayName("Check rendering a large masked body copies it no more than it has to")
    void checkRenderingLargeBodyCopiesLittle() throws IOException {
        // A large body is copied in full each time it is copied: decoded from a copy of the captured bytes,
        // then copied into a builder for the filters, back out of it and into the string logged, a body
        // masked by one filter used to take eight times its size
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());

        // Given: 1 MB of JSON carrying a password every hundred bytes or so
        StringBuilder json = new StringBuilder();
        for (int i = 0; json.length() < 1 << 20; i++) {
            json.append("{\"id\":").append(i).append(",\"name\":\"user-").append(i)
                    .append("\",\"password\":\"secret-").append(i).append("\"},");
        }
        byte[] body = json.toString().getBytes(UTF_8);
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(NO_LIMIT);
        capture.sink().write(body);
        Set<LoggedBodyFilter> filters = Set.of(new JsonMaskingBodyFilter("password"));
        for (int i = 0; i < 3; i++) {
            // Rendered first, so what is measured below is the rendering rather than loading its classes
            capture.content(filters, APPLICATION_JSON_TYPE);
        }

        // When
        long before = threads.getCurrentThreadAllocatedBytes();
        String result = capture.content(filters, APPLICATION_JSON_TYPE);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        // Then: decoded once, masked once and turned into a string once, which is about three times its size
        assertFalse(result.contains("secret-"));
        assertTrue(allocated < 4L * body.length, () -> "Allocated " + allocated + " bytes for a body of " + body.length);
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application/x-ndjson", "application/json-seq", "application/yaml",
            "application/x-yaml", "application/openapi+yaml", "application/graphql"})
    @DisplayName("Check the text formats of streaming and configuration APIs are not treated as binary")
    void checkStructuredTextMediaTypesAreNotBinary(String mediaType) {
        // Logged as hexadecimal, a bulk request of newline-delimited JSON is as good as unlogged
        assertFalse(BoundedLoggedBodyCapture.isBinary(MediaType.valueOf(mediaType)));
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
        assertEquals(FILTERING_FAILURE_MARKER, result);
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
    @DisplayName("Check a filter overflowing the stack neither breaks the exchange nor leaks the body")
    void checkFilterOverflowingTheStackDropsBody() throws IOException {
        // Given: a pattern java.util.regex recurses through once per character, as an application's own
        // RegexMaskingBodyFilter easily can, run on a payload long enough to exhaust the stack
        BoundedLoggedBodyCapture capture = new BoundedLoggedBodyCapture(-1);
        capture.sink().write(("secret=" + "ab".repeat(100_000)).getBytes(UTF_8));
        LoggedBodyFilter filter = new RegexMaskingBodyFilter("secret=((?:a|b)*)", 1);

        // When: a StackOverflowError is not an Exception, and used to escape every guard on its way out
        String result = assertDoesNotThrow(() -> capture.content(Set.of(filter)));

        // Then
        assertEquals(FILTERING_FAILURE_MARKER, result);
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
