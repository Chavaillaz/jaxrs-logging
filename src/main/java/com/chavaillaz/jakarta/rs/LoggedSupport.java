package com.chavaillaz.jakarta.rs;

import org.slf4j.event.Level;

import com.chavaillaz.jakarta.rs.client.LoggedClientFeature;

/**
 * The level {@link LoggedFilter} and {@link LoggedClientFeature} log an exchange at by default, given the
 * status it was answered with: the two providers sit on opposite sides of the JAX-RS API and share no
 * supertype, so it lives here rather than written twice and left to drift apart.
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

}
