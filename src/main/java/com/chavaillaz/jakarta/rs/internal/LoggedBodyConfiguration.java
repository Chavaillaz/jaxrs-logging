package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.NO_LIMIT;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.checkLimit;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;

import com.chavaillaz.jakarta.rs.LoggedBody;
import com.chavaillaz.jakarta.rs.LoggedBody.LogType;
import com.chavaillaz.jakarta.rs.client.LoggedClientFeature;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Body logging configuration of one direction of a resource method, or of one side of a
 * {@link LoggedClientFeature}, resolved from {@link LoggedBody} once rather than on every request.
 *
 * @param types   The types of logging to apply to the body, empty for no body logging at all
 * @param limit   The maximum number of bytes of the body to log, or {@code -1} for no limit
 * @param filters The filters to apply to the body before logging it, in the order they apply in
 */
public record LoggedBodyConfiguration(Set<LogType> types, int limit, List<LoggedBodyFilter> filters) {

    /**
     * Configuration logging nothing, used whenever no {@link LoggedBody} applies.
     */
    public static final LoggedBodyConfiguration NONE = new LoggedBodyConfiguration(Set.of(), NO_LIMIT, List.of());

    /**
     * Creates a body logging configuration, rejecting an invalid limit as the resource is resolved, rather than on
     * every capture.
     *
     * @throws IllegalArgumentException if the limit is lower than {@code -1}
     */
    public LoggedBodyConfiguration {
        requireNonNull(types, "The types of body logging are required");
        requireNonNull(filters, "The body filters are required");
        checkLimit(limit);
    }

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
