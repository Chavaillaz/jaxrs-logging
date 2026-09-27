package com.chavaillaz.jakarta.rs.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Capturing output stream")
class CapturingOutputStreamTest {

    static final String BODY = "{\"content\":\"My Article\"}";

    final ByteArrayOutputStream entity = new ByteArrayOutputStream();
    final ByteArrayOutputStream capture = new ByteArrayOutputStream();

    @Test
    @DisplayName("Check a body written in chunks and byte by byte reaches both the entity stream and the capture")
    void checkWritesCopied() throws IOException {
        // Given
        byte[] body = BODY.getBytes(UTF_8);
        OutputStream stream = new CapturingOutputStream(entity, capture);

        // When: the first byte alone, then the rest from an offset
        stream.write(body[0]);
        stream.write(body, 1, body.length - 1);

        // Then
        assertEquals(BODY, entity.toString(UTF_8));
        assertEquals(BODY, capture.toString(UTF_8));
    }

    @Test
    @DisplayName("Check the bytes the entity stream fails to write are not captured, the failure left to the caller")
    void checkFailedWriteNotCaptured() throws IOException {
        // Given: an entity stream failing past its first write, as one whose client disconnected
        AtomicInteger writes = new AtomicInteger();
        OutputStream stream = new CapturingOutputStream(new OutputStream() {

            @Override
            public void write(int b) throws IOException {
                if (writes.incrementAndGet() > 1) {
                    throw new IOException("Broken pipe");
                }
            }

        }, capture);

        // When
        stream.write('a');
        assertThrows(IOException.class, () -> stream.write('b'));
        assertThrows(IOException.class, () -> stream.write(new byte[]{'c', 'd'}, 0, 2));

        // Then
        assertEquals("a", capture.toString(UTF_8));
    }

    @Test
    @DisplayName("Check flushing and closing reach both streams, the capture closed even when the entity stream fails to")
    void checkFlushAndClose() throws IOException {
        // Given
        AtomicInteger flushes = new AtomicInteger();
        AtomicBoolean captureClosed = new AtomicBoolean();
        OutputStream counting = new OutputStream() {

            @Override
            public void write(int b) {
                // Nothing written here
            }

            @Override
            public void flush() {
                flushes.incrementAndGet();
            }

            @Override
            public void close() {
                captureClosed.set(true);
            }

        };
        OutputStream stream = new CapturingOutputStream(new OutputStream() {

            @Override
            public void write(int b) {
                // Nothing written here
            }

            @Override
            public void flush() {
                flushes.incrementAndGet();
            }

            @Override
            public void close() throws IOException {
                throw new IOException("Broken pipe");
            }

        }, counting);

        // When
        stream.flush();
        assertThrows(IOException.class, stream::close);

        // Then
        assertEquals(2, flushes.get());
        assertTrue(captureClosed.get());
    }

}
