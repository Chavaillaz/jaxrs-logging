package com.chavaillaz.jakarta.rs;

import static java.util.stream.Collectors.toSet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instantiates and caches {@link LoggedBodyFilter} instances by class, so the same filter class
 * referenced by multiple resource methods (or by both the request and response configuration of one)
 * is only reflectively instantiated once.
 * <p>
 * Not tied to any particular provider: usable from both {@link LoggedFilter} (server side) and
 * {@link LoggedClientFilter} (client side), since a {@link LoggedBodyFilter} is defined as stateless
 * and thread-safe regardless of which side captured the body it filters.
 */
public class LoggedBodyFilterFactory {

    protected static final Logger log = LoggerFactory.getLogger(LoggedBodyFilterFactory.class);

    /**
     * No-op filter cached for a body filter class that failed to be instantiated, so that failure is
     * remembered instead of being retried (and re-logged) on every single request referencing it.
     * <p>
     * A plain {@code null} cannot be used for that purpose: {@link ConcurrentHashMap#computeIfAbsent}
     * does not record a mapping when the function returns {@code null} (see its Javadoc), so returning
     * {@code null} on failure would cause the reflective instantiation (and the {@code log.error} call)
     * to be repeated on every request instead of once.
     */
    protected static final LoggedBodyFilter FAILED_BODY_FILTER = body -> {
        // No-op: the class could not be instantiated, see the error logged once at that time
    };

    /**
     * Cache of instances for request and response body filters.
     * Uses a concurrent map as an instance of this factory is typically shared across concurrently
     * processed requests.
     */
    protected final Map<Class<?>, LoggedBodyFilter> cache = new ConcurrentHashMap<>();

    /**
     * Gets the filter instances for the given filter classes, instantiating (and caching) any not
     * already resolved.
     *
     * @param filterTypes The stream of filter class arrays to instantiate (one array per resolved
     *                    annotation, as {@link LoggedBody#filters()} is itself an array)
     * @return The set of filter instances to be applied
     */
    public Set<LoggedBodyFilter> getInstances(Stream<Class<? extends LoggedBodyFilter>[]> filterTypes) {
        return filterTypes
                .flatMap(Stream::of)
                .map(this::getInstance)
                .collect(toSet());
    }

    /**
     * Gets (instantiating and caching if not already done) the instance for the given filter class.
     *
     * @param type The body filter class to be instantiated
     * @param <T>  The body filter type
     * @return The instance created, or {@link #FAILED_BODY_FILTER} if it failed
     */
    protected <T extends LoggedBodyFilter> LoggedBodyFilter getInstance(Class<T> type) {
        return cache.computeIfAbsent(type, ignored -> {
            try {
                return type.getConstructor().newInstance();
            } catch (Exception e) {
                log.error("Unable to instantiate body filter {}, it will be skipped for every subsequent request", type, e);
                return FAILED_BODY_FILTER;
            }
        });
    }

}
