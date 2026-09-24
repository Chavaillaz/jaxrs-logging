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
 * the kind of thing that silently drifts apart once written twice, which is exactly why they live here -
 * as does the capture of bodies, which they share as {@link BodyCapturer}.
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
     * Gets a value through the given action, reporting anything it throws on the given logger and falling
     * back to the given value instead, for the same reason as {@link #safely(Logger, String, Runnable)}.
     *
     * @param log      The logger to report a failure on
     * @param message  The message to report a failure with
     * @param action   The action getting the value
     * @param fallback The value to use if the action fails
     * @param <T>      The type of the value
     * @return The value the action got, or the fallback value if it failed
     */
    public static <T> T safely(Logger log, String message, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (Exception e) {
            report(log, message, e);
            return fallback;
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

}
