package com.chavaillaz.jakarta.rs;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * The decisions {@link LoggedFilter} and {@link LoggedClientFilter} make identically around every log
 * line they write.
 * <p>
 * The two providers sit on opposite sides of the JAX-RS API and are registered, configured and invoked
 * in entirely different ways - one is a {@link jakarta.ws.rs.ext.Provider} resolving its configuration
 * per resource method from annotations, the other a builder-configured instance applying uniformly to a
 * {@code Client} - so there is no supertype worth inventing to share behaviour between them. What they
 * do share is policy: how a status maps to a level, and what happens when logging itself fails. Both are
 * the kind of thing that silently drifts apart once written twice, which is exactly why they live here.
 * <p>
 * Each provider keeps its own overridable method delegating to these, so an application still customizes
 * the behaviour on the provider it is extending rather than being sent here.
 */
public final class LoggedSupport {

    private LoggedSupport() {
        // Utility class
    }

    /**
     * Gets the level at which an exchange answered with the given status is logged: {@link Level#ERROR}
     * for a server error, {@link Level#WARN} for a client error, {@link Level#INFO} otherwise.
     * <p>
     * An exchange that failed logged at the same level as one that succeeded is a log line nobody is
     * alerted on: the library writing the line is the one place that already knows which it was, and
     * leaving everything at {@code INFO} pushes that knowledge into a message-parsing rule in whatever
     * consumes the logs.
     * <p>
     * Client errors are deliberately not errors. On the server side a {@code 404} or a {@code 400} is
     * the application working as designed and says something about the caller, so alerting on it would
     * page someone for somebody else's typo; on the client side it means the application sent something
     * the downstream service rejected, which is a bug on this side rather than an outage. {@code WARN}
     * keeps both visible without either being an incident.
     *
     * @param status The status the exchange was answered with, {@code 0} when unknown
     * @return The level to log the exchange at
     */
    public static Level levelOf(int status) {
        if (status >= 500) {
            return Level.ERROR;
        } else if (status >= 400) {
            return Level.WARN;
        }
        return Level.INFO;
    }

    /**
     * Runs the given logging action, reporting anything it throws on the given logger and swallowing it.
     * <p>
     * Both providers do their logging from a {@code finally} block, so that an exchange is logged (and,
     * on the server side, its MDC cleaned up) even when the exchange itself failed. That placement makes
     * an exception thrown while logging strictly worse than useless: it <em>replaces</em> the application
     * exception on its way out, so a bug in a {@link LoggedBodyFilter}, an appender that ran out of disk
     * or a container returning an unexpected {@code null} does not merely lose a log line, it turns the
     * real failure into an unrelated one - or turns a perfectly good response into a 500.
     * <p>
     * Observability must never be the reason an exchange fails: whatever goes wrong here goes no further
     * than the given logger, and a logger that cannot even report that is swallowed too.
     *
     * @param log     The logger to report a failure on
     * @param message The message to report a failure with
     * @param action  The logging action to run
     */
    public static void safely(Logger log, String message, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            report(log, message, e);
        }
    }

    /**
     * Reports the given failure on the given logger, swallowing a logger that cannot even do that.
     *
     * @param log     The logger to report the failure on
     * @param message The message to report the failure with
     * @param failure The failure to report
     */
    static void report(Logger log, String message, Exception failure) {
        try {
            log.error(message, failure);
        } catch (Exception ignored) {
            // Nothing left to report it with: reporting must not be the thing that breaks the exchange
        }
    }

    /**
     * Runs the given body capture setup, reporting anything it throws on the given logger and returning
     * {@code null} instead, so the body is left out of the logs rather than the exchange failing.
     * <p>
     * The counterpart of {@link #safely(Logger, String, Runnable)} for the one piece of logging work both
     * providers do <em>before</em> {@code proceed()} rather than in a {@code finally} block after it, and
     * the position is exactly what makes it worth guarding separately: something going wrong while a
     * captured body is rendered for the logs costs a log line, whereas something going wrong while the
     * capture is being wired up means {@code proceed()} is never reached at all, so the entity is never
     * read or written and the exchange fails with an error having nothing to do with it.
     * <p>
     * That is not hypothetical. {@code createBodyCapture} is the documented extension point for the
     * mechanics of capture - spilling to a temporary file, for instance, which fails the way file system
     * access does - and the default capture itself rejects a {@link LoggedBody#limit()} below {@code -1},
     * which would otherwise turn a typo in an annotation into a 500 on every request to that resource.
     * <p>
     * Creating the capture and wrapping the entity stream with it are taken as two steps rather than as
     * one block precisely because of that extension point: a capture that was created and could then not
     * be wired in is a capture holding whatever it reserved - the temporary file above - with nobody left
     * to hand it back to, as {@link #endCapture} only ever sees one that was successfully put in place.
     * Keeping the two apart is what lets this release it.
     * <p>
     * The same extension point can fail later, once the capture is in place: its sink is written to as a
     * branch of the entity stream itself, so the capture handed to the wiring, and returned, guards that
     * sink (see {@link GuardedBodyCapture}) - a sink that fails leaves the body out of the logs, reported on
     * the given logger, rather than failing the read or write of the entity it was only observing.
     *
     * @param log     The logger to report a failure on
     * @param message The message to report a failure with
     * @param factory The creation of the capture
     * @param wiring  The wrapping of the entity stream with the created capture
     * @return The capture put in place, or {@code null} if it could not be
     */
    public static LoggedBodyCapture startCapture(Logger log, String message, Supplier<LoggedBodyCapture> factory, Consumer<LoggedBodyCapture> wiring) {
        LoggedBodyCapture capture = null;
        try {
            capture = factory.get();
            capture = new GuardedBodyCapture(capture, log, message);
            wiring.accept(capture);
            return capture;
        } catch (Exception e) {
            release(capture);
            report(log, message, e);
            return null;
        }
    }

    /**
     * Runs the given action reading what a capture collected, then releases that capture, reporting
     * anything either of them throws on the given logger and swallowing it.
     * <p>
     * The counterpart of {@link #startCapture}: whatever a capture was given to hold the body with, it
     * gets back here, once and whether or not reading what it collected worked. Rendering a captured
     * body is exactly the step that fails on a payload nobody expected (see
     * {@link BoundedLoggedBodyCapture}), so releasing it anywhere but a {@code finally} would leak a
     * temporary file on precisely the bodies an application most wants to look at.
     *
     * @param log     The logger to report a failure on
     * @param message The message to report a failure with
     * @param capture The capture to read from and release, never {@code null}
     * @param action  The action reading what the capture collected
     */
    public static void endCapture(Logger log, String message, LoggedBodyCapture capture, Runnable action) {
        safely(log, message, () -> {
            try {
                action.run();
            } finally {
                capture.close();
            }
        });
    }

    /**
     * Releases the given capture, if there is one, swallowing anything it throws.
     * <p>
     * Used on the path where putting a capture in place failed: something has already gone wrong and has
     * already been reported, so a capture that cannot even be released adds nothing worth a second line.
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
