package com.chavaillaz.jakarta.rs.internal;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Reports and swallows the failures of logging, so logging an exchange is never the reason it fails: thrown from
 * the {@code finally} block logging an exchange, an exception would replace the one it failed with, or turn a good
 * response into a 500.
 */
public final class LoggingGuard {

    private LoggingGuard() {
        // Utility class
    }

    /**
     * Runs the given logging action, reporting anything it throws on the given logger and swallowing it.
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
    public static <T extends @Nullable Object> T safely(Logger log, String message, Supplier<T> action, T fallback) {
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
    public static void report(Logger log, String message, Throwable failure) {
        try {
            log.error(message, failure);
        } catch (Exception ignored) {
            // Nothing left to report it with: reporting must not be the thing that breaks the exchange
        }
    }

}
