package com.chavaillaz.jakarta.rs;

import java.util.Set;

import com.chavaillaz.jakarta.rs.LoggedBody.LogType;

/**
 * Body logging configuration resolved for one direction (request or response) of one resource method,
 * or for one side of a {@link LoggedClientFilter}.
 * <p>
 * Deliberately holds ready-to-use values rather than the {@link LoggedBody} annotation it is usually
 * derived from: resolving an annotation into a {@link Set} of {@link LogType} and into instantiated
 * {@link LoggedBodyFilter}s is pure, request-independent work, so it is done once per resource method
 * (see {@link LoggedResolver}) instead of on every request. This matters because the filter asks for
 * this configuration several times per request, on the hot path of every single call the application
 * serves; building a fresh {@code Set} through a stream each time was, in aggregate, more allocation
 * than the logging itself.
 *
 * @param types   The types of logging to apply to the body, empty for no body logging at all
 * @param limit   The maximum number of bytes of the body to log, or {@code -1} for no limit
 * @param filters The filters to apply to the body before logging it, in declaration order
 */
public record LoggedBodyConfiguration(Set<LogType> types, int limit, Set<LoggedBodyFilter> filters) {

    /**
     * Configuration logging nothing, used whenever no {@link LoggedBody} applies.
     */
    public static final LoggedBodyConfiguration NONE = new LoggedBodyConfiguration(Set.of(), -1, Set.of());

    /**
     * Indicates whether the body must be captured at all for this direction.
     *
     * @return {@code true} if at least one type of body logging is activated, {@code false} otherwise
     */
    public boolean isActive() {
        return !types.isEmpty();
    }

    /**
     * Indicates whether the body must be logged using the given type.
     *
     * @param type The type of body logging to check
     * @return {@code true} if the given type is activated, {@code false} otherwise
     */
    public boolean logs(LogType type) {
        return types.contains(type);
    }

}
