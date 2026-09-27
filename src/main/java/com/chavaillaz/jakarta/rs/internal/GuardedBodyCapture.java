package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.capture.BoundedBodyCapture.FILTERING_FAILURE_MARKER;
import static com.chavaillaz.jakarta.rs.internal.BodyCapturer.CAPTURE_FAILURE;
import static com.chavaillaz.jakarta.rs.internal.BodyCapturer.FILTER_FAILURE;
import static com.chavaillaz.jakarta.rs.internal.LoggingGuard.report;

import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.chavaillaz.jakarta.rs.LoggedFeatureConfiguration;
import com.chavaillaz.jakarta.rs.capture.BoundedBodyCapture;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Decorates a {@link LoggedBodyCapture} so neither its sink nor a body filter failing fails the exchange, the
 * failure being reported on the logger of the feature capturing:
 * <ul>
 *     <li>a sink failing - a capture of the application spilling to a full disk (see
 *     {@link LoggedFeatureConfiguration.Builder#bodyCapture}) - receives nothing more, and the body is left out
 *     of the logs rather than logged in part;</li>
 *     <li>a filter failing has the body replaced with {@link BoundedBodyCapture#FILTERING_FAILURE_MARKER}, which
 *     the filters after it leave as it is: a filter that threw has not finished redacting, so what it was working
 *     on must not be logged.</li>
 * </ul>
 * The sink also stops receiving anything once the capture is released, as an entity read as a stream can go on
 * being read after its body was handed over, which would otherwise fill a capture nobody reads.
 *
 * @see BodyCapturer
 */
final class GuardedBodyCapture implements LoggedBodyCapture {

    private final LoggedBodyCapture capture;
    private final Logger log;
    private final GuardedSink sink;

    /**
     * Guards the sink and the filters of the given capture.
     *
     * @param capture The capture to guard
     * @param log     The logger of the feature capturing, to report a failure on
     */
    GuardedBodyCapture(LoggedBodyCapture capture, Logger log) {
        this.capture = capture;
        this.log = log;
        this.sink = new GuardedSink(capture.getSink(), log);
    }

    @Override
    public OutputStream getSink() {
        return sink;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Leaves the body out of the logs once the sink failed, and hands the given filters to the capture guarded.
     */
    @Override
    public @Nullable String getContent(List<LoggedBodyFilter> filters, @Nullable MediaType mediaType) {
        if (sink.failed) {
            return null;
        }
        List<LoggedBodyFilter> guarded = filters.stream()
                .<LoggedBodyFilter>map(filter -> new GuardedFilter(filter, log))
                .toList();
        return capture.getContent(guarded, mediaType);
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
        private boolean failed;
        private volatile boolean released;

        private GuardedSink(OutputStream sink, Logger log) {
            this.sink = sink;
            this.log = log;
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
            report(log, CAPTURE_FAILURE, failure);
        }

    }

    /**
     * Filter applying another one, whose failure drops the body rather than failing its rendering, and which
     * leaves a body dropped by a filter before it as it is.
     */
    private static final class GuardedFilter implements LoggedBodyFilter {

        private final LoggedBodyFilter filter;
        private final Logger log;

        private GuardedFilter(LoggedBodyFilter filter, Logger log) {
            this.filter = filter;
            this.log = log;
        }

        @Override
        public void filter(StringBuilder body) {
            if (isDropped(body)) {
                return;
            }
            try {
                filter.filter(body);
            } catch (Exception | StackOverflowError e) {
                drop(e);
                body.setLength(0);
                body.append(FILTERING_FAILURE_MARKER);
            }
        }

        @Override
        public CharSequence apply(CharSequence body) {
            if (isDropped(body)) {
                return body;
            }
            try {
                return filter.apply(body);
            } catch (Exception | StackOverflowError e) {
                drop(e);
                return FILTERING_FAILURE_MARKER;
            }
        }

        /**
         * Indicates whether the given body was dropped by a filter applied before this one.
         *
         * @param body The body to filter
         * @return {@code true} if the body was dropped, {@code false} otherwise
         */
        private static boolean isDropped(CharSequence body) {
            return FILTERING_FAILURE_MARKER.contentEquals(body);
        }

        /**
         * Reports the failure of the filter, whose body is dropped. {@link StackOverflowError} is the one
         * {@link Error} caught: how {@code java.util.regex} fails on a payload too large for its pattern, leaving
         * nothing behind once unwound.
         *
         * @param failure The failure of the filter
         */
        private void drop(Throwable failure) {
            report(log, FILTER_FAILURE, failure);
        }

    }

}
