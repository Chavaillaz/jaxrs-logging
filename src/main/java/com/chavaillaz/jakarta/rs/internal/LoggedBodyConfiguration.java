package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.NO_LIMIT;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.checkLimit;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.chavaillaz.jakarta.rs.LoggedBody.LogType;
import com.chavaillaz.jakarta.rs.LoggedBody;
import com.chavaillaz.jakarta.rs.client.LoggedClientFilter;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Body logging configuration resolved for one direction (request or response) of one resource method,
 * or for one side of a {@link LoggedClientFilter}.
 * <p>
 * Holds ready-to-use values rather than the {@link LoggedBody} annotation it is usually derived from, as it
 * is read several times per request: resolving an annotation into a {@link Set} of {@link LogType} and
 * instantiated {@link LoggedBodyFilter}s depends on no request, and is done once per resource method.
 *
 * @param types   The types of logging to apply to the body, empty for no body logging at all
 * @param limit   The maximum number of bytes of the body to log, or {@code -1} for no limit
 * @param filters The filters to apply to the body before logging it, in declaration order
 */
public record LoggedBodyConfiguration(Set<LogType> types, int limit, Set<LoggedBodyFilter> filters) {

    /**
     * Configuration logging nothing, used whenever no {@link LoggedBody} applies.
     */
    public static final LoggedBodyConfiguration NONE = new LoggedBodyConfiguration(Set.of(), NO_LIMIT, Set.of());

    /**
     * Creates a body logging configuration, rejecting one that could never capture anything.
     * <p>
     * A limit below {@code -1} is rejected here, where the configuration of a resource is resolved - once, and
     * reported along with the resource it was declared on - rather than by every capture it would be given to.
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
