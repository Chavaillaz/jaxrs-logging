package com.chavaillaz.jakarta.rs;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.event.Level;

/**
 * The policies {@link LoggedFilter} and {@link LoggedClientFilter} share around the lines they write: how
 * the status of an exchange maps to a level, and what happens when logging itself fails.
 * <p>
 * The two providers sit on opposite sides of the JAX-RS API, are configured in different ways and share no
 * supertype, so these policies live here rather than written twice and left to drift apart.
 */
public final class LoggedSupport {

    private LoggedSupport() {
        // Utility class
    }

    /**
     * Gets the level at which an exchange answered with the given status is logged: {@link Level#ERROR}
     * for a server error, {@link Level#WARN} for a client error, {@link Level#INFO} otherwise.
     * <p>
     * A failed exchange logged like a successful one is a line nobody is alerted on. A client error is no
     * error, though: on the server side, a {@code 404} or a {@code 400} says something about the caller rather
     * than about the service, and on the client side, it points at a bug of the application rather than at an
     * outage - {@code WARN} keeps both visible without either being an incident.
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
     * The providers log from {@code finally} blocks, so an exchange is logged even when it failed: an
     * exception thrown there would replace the one the exchange failed with, or turn a good response into a
     * 500. Logging must never be the reason an exchange fails, so a logger that cannot even report the
     * failure is swallowed too.
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
