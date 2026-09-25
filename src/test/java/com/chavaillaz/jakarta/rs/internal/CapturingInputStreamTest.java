package com.chavaillaz.jakarta.rs.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Capturing input stream")
class CapturingInputStreamTest {

    static final String BODY = "{\"content\":\"My Article\"}";

    final ByteArrayOutputStream capture = new ByteArrayOutputStream();

    InputStream capturing(InputStream entity) {
        return new CapturingInputStream(entity, capture);
    }

    static InputStream entity() {
        return new ByteArrayInputStream(BODY.getBytes(UTF_8));
    }

    static String readRest(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), UTF_8);
    }

    String captured() {
        return capture.toString(UTF_8);
    }

    @Test
    @DisplayName("Check a body read in chunks is captured once")
    void checkChunkedRead() throws IOException {
        // When
        String read = readRest(capturing(entity()));

        // Then
        assertEquals(BODY, read);
        assertEquals(BODY, captured());
    }

    @Test
    @DisplayName("Check a body read byte by byte is captured once")
    void checkByteRead() throws IOException {
        // Given
        InputStream stream = capturing(entity());

        // When
        StringBuilder read = new StringBuilder();
        for (int b = stream.read(); b >= 0; b = stream.read()) {
            read.append((char) b);
        }

        // Then
        assertEquals(BODY, read.toString());
        assertEquals(BODY, captured());
    }

    @Test
    @DisplayName("Check bytes read again after peeking at the stream are captured once")
    void checkPeekCapturedOnce() throws IOException {
        // Given: a reader checking for an empty entity peeks at its first byte
        InputStream stream = capturing(entity());
        stream.mark(1);
        stream.read();
        stream.reset();

        // When
        String read = readRest(stream);

        // Then
        assertEquals(BODY, read);
        assertEquals(BODY, captured());
    }

    @Test
    @DisplayName("Check a read spanning bytes captured before a reset and bytes not captured yet")
    void checkResetToMarkPartway() throws IOException {
        // Given
        InputStream stream = capturing(new BufferedInputStream(entity()));
        String start = new String(stream.readNBytes(3), UTF_8);
        stream.mark(100);
        stream.readNBytes(5);
        stream.reset();

        // When
        String read = start + readRest(stream);

        // Then
        assertEquals(BODY, read);
        assertEquals(BODY, captured());
    }

    @Test
    @DisplayName("Check a reset without a mark goes back to the start of the stream")
    void checkResetWithoutMark() throws IOException {
        // Given: a ByteArrayInputStream goes back to its start
        InputStream stream = capturing(entity());
        stream.readNBytes(4);
        stream.reset();

        // When
        String read = readRest(stream);

        // Then
        assertEquals(BODY, read);
        assertEquals(BODY, captured());
    }

    @Test
    @DisplayName("Check bytes skipped by the reader are captured all the same")
    void checkSkippedBytesCaptured() throws IOException {
        // Given
        InputStream stream = capturing(entity());

        // When
        assertEquals(9, stream.skip(9));
        stream.skipNBytes(3);
        stream.transferTo(OutputStream.nullOutputStream());

        // Then: a body logged with a hole in it would read as the one the application received
        assertEquals(BODY, captured());
    }

    @Test
    @DisplayName("Check a reset refused by the stream leaves what was captured as it is")
    void checkRefusedReset() throws IOException {
        // Given
        InputStream stream = capturing(new PushbackInputStream(entity()));
        stream.mark(10);
        stream.readNBytes(4);

        // When
        assertThrows(IOException.class, stream::reset);
        stream.transferTo(OutputStream.nullOutputStream());

        // Then
        assertEquals(BODY, captured());
    }

}
