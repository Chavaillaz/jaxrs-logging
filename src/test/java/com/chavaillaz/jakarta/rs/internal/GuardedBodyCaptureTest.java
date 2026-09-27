package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.capture.BoundedBodyCapture.FILTERING_FAILURE_MARKER;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.NO_LIMIT;
import static com.chavaillaz.jakarta.rs.internal.BodyCapturer.CAPTURE_FAILURE;
import static com.chavaillaz.jakarta.rs.internal.BodyCapturer.FILTER_FAILURE;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.MediaType;
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
import com.chavaillaz.jakarta.rs.capture.BoundedBodyCapture;
import com.chavaillaz.jakarta.rs.capture.DelegatingBodyCapture;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.filter.RegexMaskingBodyFilter;

@DisplayName("Guarded body capture")
class GuardedBodyCaptureTest extends AbstractFilterTest {

    static final Logger log = LoggerFactory.getLogger(GuardedBodyCaptureTest.class);

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

    /**
     * Counts the failures reported with the given message on the logger the capture was given, the one of the
     * feature capturing.
     */
    static long reports(String message) {
        return listAppender.getMessages().stream()
                .filter(event -> event.getMessage().getFormattedMessage().equals(message))
                .filter(event -> event.getLoggerName().equals(log.getName()))
                .count();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    @DisplayName("Check a sink failing is reported once, then left alone, and what it captured left out")
    void checkFailingSink(Operation operation) {
        // Given
        FailingSink sink = new FailingSink(operation);
        GuardedBodyCapture capture = new GuardedBodyCapture(new DelegatingBodyCapture(NO_LIMIT) {

            @Override
            public OutputStream getSink() {
                return sink;
            }

        }, log);
        OutputStream guarded = capture.getSink();

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
        assertEquals(1, reports(CAPTURE_FAILURE));
        assertNull(capture.getContent(List.of(), TEXT_PLAIN_TYPE));
    }

    @Test
    @DisplayName("Check a sink that does not fail passes everything through, and the capture is released")
    void checkWorkingSink() throws IOException {
        // Given
        AtomicBoolean closed = new AtomicBoolean();
        GuardedBodyCapture capture = new GuardedBodyCapture(new DelegatingBodyCapture(NO_LIMIT) {

            @Override
            public void close() {
                closed.set(true);
            }

        }, log);

        // When
        capture.getSink().write("body".getBytes(UTF_8));
        capture.getSink().flush();
        String content = capture.getContent(List.of(), TEXT_PLAIN_TYPE);
        capture.close();

        // Then
        assertEquals("body", content);
        assertTrue(closed.get());
        assertEquals(0, reports(CAPTURE_FAILURE));
    }

    @Test
    @DisplayName("Check a sink written to once its capture was released passes nothing on")
    void checkReleasedSink() throws IOException {
        // Given: an entity read as a stream, which its reader only goes through once the capture is released
        AtomicInteger writesAfterRelease = new AtomicInteger();
        AtomicBoolean released = new AtomicBoolean();
        GuardedBodyCapture capture = new GuardedBodyCapture(new DelegatingBodyCapture(NO_LIMIT) {

            @Override
            public OutputStream getSink() {
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

        }, log);
        OutputStream sink = capture.getSink();
        capture.close();

        // When
        sink.write('a');
        sink.write("body".getBytes(UTF_8), 0, 4);
        sink.flush();
        sink.close();

        // Then: nothing reached the capture, as what it would collect from now on could never be logged
        assertEquals(0, writesAfterRelease.get());
        assertEquals(0, reports(CAPTURE_FAILURE));
    }

    @Test
    @DisplayName("Check a filter that throws drops the body instead of leaking it unfiltered, and is reported")
    void checkFailingFilterDropsBody() throws IOException {
        // A filter is a "this must never reach the logs" instruction, so a filter that threw halfway
        // through must not result in the raw payload being written: what it was redacting is precisely
        // what must not appear
        GuardedBodyCapture capture = new GuardedBodyCapture(new BoundedBodyCapture(NO_LIMIT), log);
        capture.getSink().write("{\"password\":\"hunter2\"}".getBytes(UTF_8));

        // When: a broken filter breaking the request it was only meant to be logging is the one outcome this
        // must never have
        String result = assertDoesNotThrow(() -> capture.getContent(List.of(body -> {
            throw new IllegalStateException("Filter bug");
        }), TEXT_PLAIN_TYPE));

        // Then
        assertEquals(FILTERING_FAILURE_MARKER, result);
        assertEquals(1, reports(FILTER_FAILURE));
    }

    @Test
    @DisplayName("Check a filter overflowing the stack neither breaks the exchange nor leaks the body")
    void checkFilterOverflowingTheStackDropsBody() throws IOException {
        // Given: a pattern java.util.regex recurses through once per character, as an application's own
        // RegexMaskingBodyFilter easily can, run on a payload long enough to exhaust the stack
        GuardedBodyCapture capture = new GuardedBodyCapture(new BoundedBodyCapture(NO_LIMIT), log);
        capture.getSink().write(("secret=" + "ab".repeat(100_000)).getBytes(UTF_8));
        LoggedBodyFilter filter = new RegexMaskingBodyFilter("secret=((?:a|b)*)", 1);

        // When: a StackOverflowError is not an Exception, and used to escape every guard on its way out
        String result = assertDoesNotThrow(() -> capture.getContent(List.of(filter), null));

        // Then
        assertEquals(FILTERING_FAILURE_MARKER, result);
        assertEquals(1, reports(FILTER_FAILURE));
    }

    @Test
    @DisplayName("Check the filters after one that failed leave the body it dropped as it is")
    void checkFiltersAfterFailureSkipped() throws IOException {
        // Given: a filter after the failing one, rewriting whatever it is given
        AtomicInteger applied = new AtomicInteger();
        GuardedBodyCapture capture = new GuardedBodyCapture(new BoundedBodyCapture(NO_LIMIT), log);
        capture.getSink().write("content".getBytes(UTF_8));

        // When
        String result = capture.getContent(List.of(body -> {
            throw new IllegalStateException("Filter bug");
        }, body -> {
            applied.incrementAndGet();
            body.append(" rewritten");
        }), TEXT_PLAIN_TYPE);

        // Then: the log shows the body was dropped, whatever the filters after that one do
        assertEquals(FILTERING_FAILURE_MARKER, result);
        assertEquals(0, applied.get());
    }

    @Test
    @DisplayName("Check a capture filtering in place drops the body a filter fails on as well")
    void checkFailingFilterInPlaceDropsBody() {
        // Given: a capture of the application calling filter(StringBuilder) rather than apply(CharSequence)
        GuardedBodyCapture capture = new GuardedBodyCapture(new LoggedBodyCapture() {

            @Override
            public OutputStream getSink() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public String getContent(List<LoggedBodyFilter> filters, MediaType mediaType) {
                StringBuilder body = new StringBuilder("{\"password\":\"hunter2\"}");
                filters.forEach(filter -> filter.filter(body));
                return body.toString();
            }

        }, log);

        // When: followed by a filter rewriting whatever it is given
        AtomicInteger applied = new AtomicInteger();
        String result = assertDoesNotThrow(() -> capture.getContent(List.of(body -> {
            body.setLength(5);
            throw new IllegalStateException("Filter bug");
        }, body -> {
            applied.incrementAndGet();
            body.append(" rewritten");
        }), TEXT_PLAIN_TYPE));

        // Then: what the filter left half done goes with the rest
        assertEquals(FILTERING_FAILURE_MARKER, result);
        assertEquals(0, applied.get());
        assertEquals(1, reports(FILTER_FAILURE));
    }

}
