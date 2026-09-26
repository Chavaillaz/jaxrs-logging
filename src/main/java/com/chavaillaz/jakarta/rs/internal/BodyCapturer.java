package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.internal.LoggingGuard.report;
import static com.chavaillaz.jakarta.rs.internal.LoggingGuard.safely;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.InterceptorContext;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import org.apache.commons.io.output.TeeOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.chavaillaz.jakarta.rs.LoggedFilter;
import com.chavaillaz.jakarta.rs.LoggedFilterConfiguration;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.client.LoggedClientFilter;

/**
 * Captures the body of an entity as it is read or written, for a provider to log: copies the entity stream to
 * a {@link LoggedBodyCapture}, and hands over the body it renders once the entity is done, without any of it
 * being allowed to fail the exchange it observes. Shared by {@link LoggedFilter} and
 * {@link LoggedClientFilter}, which differ only in what they do with a body once captured.
 * <p>
 * Each step is guarded, as a capture of the application's own (see
 * {@link LoggedFilterConfiguration.Builder#bodyCapture}) - one spilling to a temporary file, say - can fail
 * the way file system access does:
 * <ul>
 *     <li>putting the capture in place happens before the entity is read or written, which a failure must
 *     not prevent, and a capture created but not wired in is released right away;</li>
 *     <li>rendering what was captured happens once the entity is done, and the capture is released whether
 *     that worked or not;</li>
 *     <li>the sink of the capture, written to along with the entity stream, is guarded by
 *     {@link GuardedBodyCapture}.</li>
 * </ul>
 */
public final class BodyCapturer {

    /**
     * Message reporting a body that could not be captured.
     */
    public static final String CAPTURE_FAILURE = "Unable to capture the body, it is left out of the logs, the exchange itself is left unaffected";

    /**
     * Message reporting a body that was captured but could not be rendered, or a capture that could not be
     * released.
     */
    public static final String RENDERING_FAILURE = "Unable to log the captured body or to release the capture, the exchange itself is left unaffected";

    /**
     * Message reporting a body that was captured but is too large to be rendered with the memory available.
     */
    public static final String MEMORY_FAILURE = "Unable to render the captured body with the memory available, it is left out of the logs, the exchange itself is left unaffected";

    private final Logger log;
    private final IntFunction<LoggedBodyCapture> captures;

    /**
     * Creates a capturer reporting on the given logger and capturing through the given captures.
     *
     * @param log      The logger of the provider capturing, to report a failure on
     * @param captures The creation of a capture keeping at most the given number of bytes
     */
    public BodyCapturer(Logger log, IntFunction<LoggedBodyCapture> captures) {
        this.log = log;
        this.captures = captures;
    }

    /**
     * Reads the entity of the given context, capturing its body along the way if the given configuration
     * logs it.
     * <p>
     * The body of an entity read as a stream - an {@link InputStream} or a {@link Reader} the application
     * reads once the providers are done with it, as the parameter of a resource method or the entity of a
     * client response - is handed over once that stream ends, read to its end or closed, and the body of any
     * other entity once it is read. The handing over of a stream is given to the provider as well, for it to
     * run once its exchange is done, if the stream has not ended by then: it hands over as much of the body
     * as the application read, whatever it reads next going uncaptured.
     *
     * @param context       The context of the entity being read
     * @param configuration The body logging configuration of the entity
     * @param handler       What to do with the body captured, which is {@code null} when its capture failed
     * @param streamed      What to do with the handing over of a body read as a stream, which runs once,
     *                      whatever runs it first
     * @return The entity read
     * @throws IOException             if an IO error arises while reading the entity
     * @throws WebApplicationException if the entity cannot be read
     */
    public @Nullable Object read(ReaderInterceptorContext context, LoggedBodyConfiguration configuration, Consumer<@Nullable String> handler, Consumer<Runnable> streamed) throws IOException, WebApplicationException {
        if (!configuration.isActive()) {
            return context.proceed();
        }

        // Made along with the capture it hands over, and run by whichever of the end of the stream, the end of
        // the read and the end of the exchange gets there first
        AtomicReference<@Nullable Runnable> handingOver = new AtomicReference<>();
        LoggedBodyCapture capture = start(configuration, started -> {
            Runnable handOver = once(() -> end(started, configuration, context, handler));
            handingOver.set(handOver);
            context.setInputStream(new CapturingInputStream(context.getInputStream(), started.sink(), handOver));
        });
        Runnable handOver = handingOver.get();
        if (capture == null || handOver == null) {
            return context.proceed();
        }

        boolean streaming = false;
        try {
            Object entity = context.proceed();
            streaming = entity instanceof InputStream || entity instanceof Reader;
            if (streaming) {
                streamed.accept(handOver);
            }
            return entity;
        } finally {
            // Hands over whatever was captured even if reading the entity failed (a malformed payload), so
            // a deserialization error does not leave the body out of the logs
            if (!streaming) {
                handOver.run();
            }
        }
    }

    /**
     * Writes the entity of the given context, capturing its body along the way if the given configuration
     * logs it.
     *
     * @param context       The context of the entity being written
     * @param configuration The body logging configuration of the entity
     * @param handler       What to do with the body captured, which is {@code null} when its capture failed
     * @throws IOException             if an IO error arises while writing the entity
     * @throws WebApplicationException if the entity cannot be written
     */
    public void write(WriterInterceptorContext context, LoggedBodyConfiguration configuration, Consumer<@Nullable String> handler) throws IOException, WebApplicationException {
        if (!configuration.isActive()) {
            context.proceed();
            return;
        }

        LoggedBodyCapture capture = start(configuration,
                started -> context.setOutputStream(new TeeOutputStream(context.getOutputStream(), started.sink())));
        try {
            context.proceed();
        } finally {
            // Hands over whatever was captured even if writing the entity failed (a client disconnecting
            // mid-write, a serialization error), so the bytes already produced are not discarded
            end(capture, configuration, context, handler);
        }
    }

    /**
     * Creates a capture and wraps the entity stream with it, reporting anything either step throws and
     * returning {@code null} instead, so the body is left out of the logs rather than the exchange failing.
     * <p>
     * The two steps are taken apart rather than as one block so that a capture created and then not wired
     * in can be released, as {@link #end} only ever sees one put in place.
     *
     * @param configuration The body logging configuration of the entity
     * @param wiring        The wrapping of the entity stream with the created capture
     * @return The capture put in place, or {@code null} if it could not be
     */
    private @Nullable LoggedBodyCapture start(LoggedBodyConfiguration configuration, Consumer<LoggedBodyCapture> wiring) {
        LoggedBodyCapture capture = null;
        try {
            capture = captures.apply(configuration.limit());
            capture = new GuardedBodyCapture(capture, log, CAPTURE_FAILURE);
            wiring.accept(capture);
            return capture;
        } catch (Exception e) {
            release(capture);
            report(log, CAPTURE_FAILURE, e);
            return null;
        }
    }

    /**
     * Hands the body the given capture rendered over to the given handler, then releases the capture,
     * reporting anything either of them throws: whatever a capture was given to hold the body with, it gets
     * back here, once, and whether or not rendering what it collected worked.
     * <p>
     * Rendering a body takes about as much memory again as capturing it did, which the heap may not have
     * for a large one - one its capture was cut short for lack of memory, typically. Only that allocation
     * fails, so the body is left out of the logs, as it would be if a filter had failed on it, rather than
     * the error escaping to fail the exchange.
     *
     * @param capture       The capture to read from and release, {@code null} if none could be put in place
     * @param configuration The body logging configuration of the entity
     * @param context       The context of the entity read or written
     * @param handler       What to do with the body captured
     */
    private void end(@Nullable LoggedBodyCapture capture, LoggedBodyConfiguration configuration, InterceptorContext context, Consumer<@Nullable String> handler) {
        if (capture == null) {
            return;
        }
        safely(log, RENDERING_FAILURE, () -> {
            try {
                handler.accept(capture.content(configuration.filters(), context.getMediaType()));
            } catch (OutOfMemoryError e) {
                report(log, MEMORY_FAILURE, e);
            } finally {
                capture.close();
            }
        });
    }

    /**
     * Makes the given action run once at most, however many times, and from however many threads, it is run.
     *
     * @param action The action to run once
     * @return The action running the given one once
     */
    private static Runnable once(Runnable action) {
        AtomicBoolean ran = new AtomicBoolean();
        return () -> {
            if (ran.compareAndSet(false, true)) {
                action.run();
            }
        };
    }

    /**
     * Releases the given capture, if there is one, swallowing anything it throws.
     * <p>
     * Used on the path where putting a capture in place failed: something has already gone wrong and is
     * being reported, so a capture that cannot even be released adds nothing worth a second line.
     *
     * @param capture The capture to release, possibly {@code null}
     */
    private static void release(@Nullable LoggedBodyCapture capture) {
        try {
            if (capture != null) {
                capture.close();
            }
        } catch (Exception ignored) {
            // Best effort: the failure that led here is the one being reported
        }
    }

}
