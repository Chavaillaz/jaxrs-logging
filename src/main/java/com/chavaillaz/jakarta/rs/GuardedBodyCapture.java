package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedSupport.report;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;

/**
 * Decorates a {@link LoggedBodyCapture} so that its sink failing does not fail the exchange it observes.
 * <p>
 * The sink is written to along with the entity stream, so whatever it throws - a capture of the application's
 * own spilling to a temporary file on a full disk, for instance (see
 * {@link LoggedFilterConfiguration.Builder#bodyCapture}) - would surface in the middle of reading or writing
 * the entity. Its first failure is reported and swallowed instead, and the sink receives nothing from then on.
 * What it collected until then is not logged at all: a body missing an arbitrary part reads, in the logs, as
 * the one the application handled, which is worse than no body.
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
     * <p>
     * Written to once per chunk of the entity - once per byte for a reader reading a byte at a time - so
     * each method forwards directly, rather than through a shared helper taking the operation as a lambda
     * that could cost an allocation per write. Confined to the thread reading or writing the entity, as the
     * capture itself is (see {@link CaptureBuffer}), so the flag needs no synchronization either.
     */
    private static final class GuardedSink extends OutputStream {

        private final OutputStream sink;
        private final Logger log;
        private final String message;
        private boolean failed;

        private GuardedSink(OutputStream sink, Logger log, String message) {
            this.sink = sink;
            this.log = log;
            this.message = message;
        }

        @Override
        public void write(int b) {
            if (!failed) {
                try {
                    sink.write(b);
                } catch (IOException | RuntimeException e) {
                    fail(e);
                }
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (!failed) {
                try {
                    sink.write(b, off, len);
                } catch (IOException | RuntimeException e) {
                    fail(e);
                }
            }
        }

        @Override
        public void flush() {
            if (!failed) {
                try {
                    sink.flush();
                } catch (IOException | RuntimeException e) {
                    fail(e);
                }
            }
        }

        @Override
        public void close() {
            // Closed along with the entity stream by a TeeOutputStream: a sink failing to close may not have
            // written out what it was given, which makes its content as unreliable as a failed write does
            if (!failed) {
                try {
                    sink.close();
                } catch (IOException | RuntimeException e) {
                    fail(e);
                }
            }
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
