package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.NO_LIMIT;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chavaillaz.jakarta.rs.AbstractFilterTest;
import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;

@DisplayName("Guarded body capture")
class GuardedBodyCaptureTest extends AbstractFilterTest {

    static final Logger log = LoggerFactory.getLogger(GuardedBodyCaptureTest.class);
    static final String FAILURE = "Unable to capture the body";

    enum Operation {
        WRITE_BYTE, WRITE_ARRAY, FLUSH, CLOSE
    }

    /**
     * Sink failing on one operation, standing in for a capture spilling to a temporary file on a full disk,
     * and counting what still reaches it once it failed.
     */
    static final class FailingSink extends OutputStream {

        final Operation failing;
        boolean failed;
        int callsAfterFailure;

        FailingSink(Operation failing) {
            this.failing = failing;
        }

        void check(Operation operation) throws IOException {
            if (failed) {
                callsAfterFailure++;
            }
            if (operation == failing) {
                failed = true;
                throw new IOException("No space left on device");
            }
        }

        @Override
        public void write(int b) throws IOException {
            check(Operation.WRITE_BYTE);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check(Operation.WRITE_ARRAY);
        }

        @Override
        public void flush() throws IOException {
            check(Operation.FLUSH);
        }

        @Override
        public void close() throws IOException {
            check(Operation.CLOSE);
        }

    }

    static long reports() {
        return listAppender.getMessages().stream()
                .filter(event -> event.getMessage().getFormattedMessage().equals(FAILURE))
                .count();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    @DisplayName("Check a sink failing is reported once, then left alone, and what it captured left out")
    void checkFailingSink(Operation operation) {
        // Given
        FailingSink sink = new FailingSink(operation);
        GuardedBodyCapture capture = new GuardedBodyCapture(new BoundedLoggedBodyCapture(NO_LIMIT) {

            @Override
            public OutputStream sink() {
                return sink;
            }

        }, log, FAILURE);
        OutputStream guarded = capture.sink();

        // When: every operation twice, the first round to fail and the second to find the sink left alone
        assertDoesNotThrow(() -> {
            for (int round = 0; round < 2; round++) {
                guarded.write('a');
                guarded.write(new byte[]{'b'}, 0, 1);
                guarded.flush();
                guarded.close();
            }
        });

        // Then: a body missing an arbitrary part of it would read as the one the application handled
        assertEquals(0, sink.callsAfterFailure);
        assertEquals(1, reports());
        assertNull(capture.content(List.of(), TEXT_PLAIN_TYPE));
    }

    @Test
    @DisplayName("Check a sink that does not fail passes everything through, and the capture is released")
    void checkWorkingSink() throws IOException {
        // Given
        AtomicBoolean closed = new AtomicBoolean();
        GuardedBodyCapture capture = new GuardedBodyCapture(new BoundedLoggedBodyCapture(NO_LIMIT) {

            @Override
            public void close() {
                closed.set(true);
            }

        }, log, FAILURE);

        // When
        capture.sink().write("body".getBytes(UTF_8));
        capture.sink().flush();
        String content = capture.content(List.of(), TEXT_PLAIN_TYPE);
        capture.close();

        // Then
        assertEquals("body", content);
        assertTrue(closed.get());
        assertEquals(0, reports());
    }

    @Test
    @DisplayName("Check a sink written to once its capture was released passes nothing on")
    void checkReleasedSink() throws IOException {
        // Given: an entity read as a stream, which its reader only goes through once the capture is released
        AtomicInteger writesAfterRelease = new AtomicInteger();
        AtomicBoolean released = new AtomicBoolean();
        GuardedBodyCapture capture = new GuardedBodyCapture(new BoundedLoggedBodyCapture(NO_LIMIT) {

            @Override
            public OutputStream sink() {
                return new OutputStream() {

                    @Override
                    public void write(int b) {
                        count();
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        count();
                    }

                    @Override
                    public void flush() {
                        count();
                    }

                    @Override
                    public void close() {
                        count();
                    }

                    void count() {
                        if (released.get()) {
                            writesAfterRelease.incrementAndGet();
                        }
                    }

                };
            }

            @Override
            public void close() {
                released.set(true);
            }

        }, log, FAILURE);
        OutputStream sink = capture.sink();
        capture.close();

        // When
        sink.write('a');
        sink.write("body".getBytes(UTF_8), 0, 4);
        sink.flush();
        sink.close();

        // Then: nothing reached the capture, as what it would collect from now on could never be logged
        assertEquals(0, writesAfterRelease.get());
        assertEquals(0, reports());
    }

}
