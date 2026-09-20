package com.chavaillaz.jakarta.rs;

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
            try {
                log.error(message, e);
            } catch (Exception ignored) {
                // Nothing left to report it with: reporting must not be the thing that breaks the exchange
            }
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
     *
     * @param log     The logger to report a failure on
     * @param message The message to report a failure with
     * @param setup   The setup creating the capture and wrapping the entity stream with it
     * @return The capture put in place, or {@code null} if it could not be
     */
    public static LoggedBodyCapture startCapture(Logger log, String message, Supplier<LoggedBodyCapture> setup) {
        try {
            return setup.get();
        } catch (Exception e) {
            try {
                log.error(message, e);
            } catch (Exception ignored) {
                // Nothing left to report it with: reporting must not be the thing that breaks the exchange
            }
            return null;
        }
    }

}
