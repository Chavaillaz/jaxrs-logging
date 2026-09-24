package com.chavaillaz.jakarta.rs;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;

/**
 * Decorates a {@link LoggedBodyCapture} so that its sink failing does not fail the exchange it observes.
 * <p>
 * A capture's sink is written to as a branch of the entity stream itself - the other side of a
 * {@code TeeInputStream} or {@code TeeOutputStream} - so whatever it throws surfaces in the middle of reading
 * or writing the entity, and fails the exchange with an error having nothing to do with it. The in-memory
 * capture this library ships cannot fail that way, but the documented use of {@code createBodyCapture} is
 * precisely one that can: a capture spilling large bodies to a temporary file fails the way file system
 * access does, a full disk included.
 * <p>
 * The first failure of the sink is therefore reported and swallowed, and the sink receives nothing from then
 * on. What it collected until then is not logged at all: a body missing an arbitrary part of it reads, in the
 * logs, as the one the application actually handled, which is worse than no body.
 *
 * @see LoggedSupport#startCapture
 */
final class GuardedBodyCapture implements LoggedBodyCapture {

    private final LoggedBodyCapture capture;
    private final GuardedSink sink;

    /**
     * Guards the sink of the given capture.
     *
     * @param capture The capture to guard
     * @param log     The logger to report a failure of its sink on
     * @param message The message to report a failure of its sink with
     */
    GuardedBodyCapture(LoggedBodyCapture capture, Logger log, String message) {
        this.capture = capture;
        this.sink = new GuardedSink(capture.sink(), log, message);
    }

    @Override
    public OutputStream sink() {
        return sink;
    }

    @Override
    public String content(Set<LoggedBodyFilter> filters) {
        return sink.failed ? null : capture.content(filters);
    }

    @Override
    public String content(Set<LoggedBodyFilter> filters, MediaType mediaType) {
        return sink.failed ? null : capture.content(filters, mediaType);
    }

    @Override
    public void close() {
        capture.close();
    }

    /**
     * Sink forwarding everything to the one of the guarded capture until that one fails.
     */
    private static final class GuardedSink extends OutputStream {

        private final OutputStream sink;
        private final Logger log;
        private final String message;
        private volatile boolean failed;

        private GuardedSink(OutputStream sink, Logger log, String message) {
            this.sink = sink;
            this.log = log;
            this.message = message;
        }

        @Override
        public void write(int b) {
            forward(() -> sink.write(b));
        }

        @Override
        public void write(byte[] b, int off, int len) {
            forward(() -> sink.write(b, off, len));
        }

        @Override
        public void flush() {
            forward(sink::flush);
        }

        @Override
        public void close() {
            // Closed along with the entity stream by a TeeOutputStream: a sink failing to close may not have
            // written out what it was given, which makes its content as unreliable as a failed write does
            forward(sink::close);
        }

        private void forward(SinkOperation operation) {
            if (failed) {
                return;
            }
            try {
                operation.run();
            } catch (IOException | RuntimeException e) {
                failed = true;
                LoggedSupport.report(log, message, e);
            }
        }

    }

    /**
     * Operation on the guarded sink.
     */
    @FunctionalInterface
    private interface SinkOperation {

        void run() throws IOException;

    }

}
