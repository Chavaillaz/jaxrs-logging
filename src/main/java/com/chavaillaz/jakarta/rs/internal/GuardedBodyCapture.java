package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.internal.LoggingGuard.report;

import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.chavaillaz.jakarta.rs.LoggedFilterConfiguration;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Decorates a {@link LoggedBodyCapture} so its sink failing - a capture of the application spilling to a full
 * disk (see {@link LoggedFilterConfiguration.Builder#bodyCapture}) - does not fail the exchange: the first
 * failure is reported, the sink receives nothing more, and the body is left out of the logs rather than logged
 * in part.
 * <p>
 * The sink also stops receiving anything once the capture is released, as an entity read as a stream can go on
 * being read after its body was handed over, which would otherwise fill a capture nobody reads.
 *
 * @see BodyCapturer
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
    public @Nullable String content(Set<LoggedBodyFilter> filters) {
        return sink.failed ? null : capture.content(filters);
    }

    @Override
    public @Nullable String content(Set<LoggedBodyFilter> filters, @Nullable MediaType mediaType) {
        return sink.failed ? null : capture.content(filters, mediaType);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Stops the sink first, so nothing reaches a capture being released.
     */
    @Override
    public void close() {
        sink.released = true;
        capture.close();
    }

    /**
     * Sink forwarding everything to the one of the guarded capture until that one fails or the capture is
     * released. Each method forwards directly, as it runs once per chunk of the entity. Confined to the thread
     * reading or writing the entity but for the release, possibly on another thread, hence the volatile flag.
     */
    private static final class GuardedSink extends OutputStream {

        private final OutputStream sink;
        private final Logger log;
        private final String message;
        private boolean failed;
        private volatile boolean released;

        private GuardedSink(OutputStream sink, Logger log, String message) {
            this.sink = sink;
            this.log = log;
            this.message = message;
        }

        @Override
        public void write(int b) {
            if (isOpen()) {
                try {
                    sink.write(b);
                } catch (IOException | RuntimeException e) {
                    fail(e);
                }
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (isOpen()) {
                try {
                    sink.write(b, off, len);
                } catch (IOException | RuntimeException e) {
                    fail(e);
                }
            }
        }

        @Override
        public void flush() {
            if (isOpen()) {
                try {
                    sink.flush();
                } catch (IOException | RuntimeException e) {
                    fail(e);
                }
            }
        }

        @Override
        public void close() {
            // Closed along with the entity stream: a sink failing to close may not have written what it was given
            if (isOpen()) {
                try {
                    sink.close();
                } catch (IOException | RuntimeException e) {
                    fail(e);
                }
            }
        }

        /**
         * Indicates whether the sink of the guarded capture is still to be written to.
         *
         * @return {@code true} if it neither failed nor was released, {@code false} otherwise
         */
        private boolean isOpen() {
            return !failed && !released;
        }

        /**
         * Records the sink as failed, so it receives nothing more and its content is left out of the logs,
         * and reports the failure.
         *
         * @param failure The failure of the sink
         */
        private void fail(Exception failure) {
            failed = true;
            report(log, message, failure);
        }

    }

}
