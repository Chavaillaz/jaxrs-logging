package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedSupport.report;
import static com.chavaillaz.jakarta.rs.LoggedSupport.safely;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.InterceptorContext;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.apache.commons.io.output.TeeOutputStream;
import org.slf4j.Logger;

/**
 * Captures the body of an entity as it is read or written, for a provider to log: copies the entity stream to
 * a {@link LoggedBodyCapture}, and hands over the body it renders once the entity is done, without any of it
 * being allowed to fail the exchange it observes.
 * <p>
 * Shared by {@link LoggedFilter} and {@link LoggedClientFilter}, which capture bodies the same way on both
 * sides of the JAX-RS API - the request read and the response written on the server, the request written
 * and the response read on the client - and differ only in what they do with the body once captured.
 * <p>
 * Two steps of a capture are guarded apart, because they fail in different places:
 * <ul>
 *     <li>putting the capture in place happens <em>before</em> {@code proceed()}, so a failure there does
 *     not merely lose a log line, it keeps the entity from being read or written at all - and a capture
 *     that was created and could then not be wired in is one holding whatever it reserved, with nobody left
 *     to release it but this class;</li>
 *     <li>rendering what was captured and releasing the capture happen once the entity is done, rendering
 *     being exactly the step that fails on a payload nobody expected, which is precisely when a capture
 *     holding more than memory - a temporary file - must not be left behind.</li>
 * </ul>
 * That is not hypothetical: a capture of one's own (see {@link LoggedFilterConfiguration.Builder#bodyCapture})
 * is the documented extension point for the mechanics of capture, spilling to a temporary file for instance,
 * which fails the way file system access does.
 * The capture put in place also guards its own sink (see {@link GuardedBodyCapture}), written to as a
 * branch of the entity stream itself.
 */
final class BodyCapturer {

    /**
     * Message reporting a body that could not be captured.
     */
    static final String CAPTURE_FAILURE = "Unable to capture the body, it is left out of the logs, the exchange itself is left unaffected";

    /**
     * Message reporting a body that was captured but could not be rendered, or a capture that could not be
     * released.
     */
    static final String RENDERING_FAILURE = "Unable to log the captured body or to release the capture, the exchange itself is left unaffected";

    private final Logger log;
    private final IntFunction<LoggedBodyCapture> captures;

    /**
     * Creates a capturer reporting on the given logger and capturing through the given captures.
     *
     * @param log      The logger of the provider capturing, to report a failure on
     * @param captures The creation of a capture keeping at most the given number of bytes
     */
    BodyCapturer(Logger log, IntFunction<LoggedBodyCapture> captures) {
        this.log = log;
        this.captures = captures;
    }

    /**
     * Reads the entity of the given context, capturing its body along the way if the given configuration
     * logs it.
     *
     * @param context       The context of the entity being read
     * @param configuration The body logging configuration of the entity
     * @param handler       What to do with the body captured, which is {@code null} when its capture failed
     * @return The entity read
     * @throws IOException             if an IO error arises while reading the entity
     * @throws WebApplicationException if the entity cannot be read
     */
    Object read(ReaderInterceptorContext context, LoggedBodyConfiguration configuration, Consumer<String> handler) throws IOException, WebApplicationException {
        if (!configuration.isActive()) {
            return context.proceed();
        }

        LoggedBodyCapture capture = start(configuration,
                started -> context.setInputStream(new CapturingInputStream(context.getInputStream(), started.sink())));
        try {
            return context.proceed();
        } finally {
            // Hands over whatever was captured even if reading the entity failed (a malformed payload), so
            // a deserialization error does not leave the body out of the logs
            end(capture, configuration, context, handler);
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
    void write(WriterInterceptorContext context, LoggedBodyConfiguration configuration, Consumer<String> handler) throws IOException, WebApplicationException {
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
    private LoggedBodyCapture start(LoggedBodyConfiguration configuration, Consumer<LoggedBodyCapture> wiring) {
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
     *
     * @param capture       The capture to read from and release, {@code null} if none could be put in place
     * @param configuration The body logging configuration of the entity
     * @param context       The context of the entity read or written
     * @param handler       What to do with the body captured
     */
    private void end(LoggedBodyCapture capture, LoggedBodyConfiguration configuration, InterceptorContext context, Consumer<String> handler) {
        if (capture == null) {
            return;
        }
        safely(log, RENDERING_FAILURE, () -> {
            try {
                handler.accept(capture.content(configuration.filters(), context.getMediaType()));
            } finally {
                capture.close();
            }
        });
    }

    /**
     * Releases the given capture, if there is one, swallowing anything it throws.
     * <p>
     * Used on the path where putting a capture in place failed: something has already gone wrong and is
     * being reported, so a capture that cannot even be released adds nothing worth a second line.
     *
     * @param capture The capture to release, possibly {@code null}
     */
    private static void release(LoggedBodyCapture capture) {
        try {
            if (capture != null) {
                capture.close();
            }
        } catch (Exception ignored) {
            // Best effort: the failure that led here is the one being reported
        }
    }

}
