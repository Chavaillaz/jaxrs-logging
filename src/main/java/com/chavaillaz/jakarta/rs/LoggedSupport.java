package com.chavaillaz.jakarta.rs;

import org.slf4j.event.Level;

import com.chavaillaz.jakarta.rs.client.LoggedClientFeature;

/**
 * The level {@link LoggedFeature} and {@link LoggedClientFeature} log an exchange at by default, given the
 * status it was answered with.
 */
public final class LoggedSupport {

    private LoggedSupport() {
        // Utility class
    }

    /**
     * Gets the level at which an exchange answered with the given status is logged: {@link Level#ERROR}
     * for a server error, {@link Level#WARN} for a client error, {@link Level#INFO} otherwise.
     * <p>
     * A client error is a warning rather than an error: on the server side, it says something about the caller
     * rather than the service, and on the client side, it points at a bug rather than an outage.
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

}
