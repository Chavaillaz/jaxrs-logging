package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.BoundedLoggedBodyCapture.FILTERING_FAILURE_MARKER;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Logger reporting a body filter class that cannot be instantiated.
     */
    protected static final Logger log = LoggerFactory.getLogger(LoggedBodyFilterFactory.class);

    /**
     * Filter cached for a body filter class that failed to be instantiated, so that failure is remembered
     * instead of being retried (and re-logged) on every single request referencing it.
     * <p>
     * It drops the body it is given, writing {@link BoundedLoggedBodyCapture#FILTERING_FAILURE_MARKER} in
     * its place, rather than leaving it untouched: a filter is a "this must never reach the logs"
     * instruction, and one that could not even be created has redacted nothing, which is the same reason a
     * filter throwing on a body has that body dropped. A mistake as small as a constructor that is not public
     * would otherwise have every payload the filter protects logged in the clear.
     * <p>
     * A plain {@code null} cannot be used for that purpose: {@link ConcurrentHashMap#computeIfAbsent}
     * does not record a mapping when the function returns {@code null} (see its Javadoc), so returning
     * {@code null} on failure would cause the reflective instantiation (and the {@code log.error} call)
     * to be repeated on every request instead of once.
     */
    protected static final LoggedBodyFilter FAILED_BODY_FILTER = new LoggedBodyFilter() {

        @Override
        public void filter(StringBuilder body) {
            body.setLength(0);
            body.append(FILTERING_FAILURE_MARKER);
        }

        @Override
        public CharSequence apply(CharSequence body) {
            // Dropped without being copied first only to be thrown away
            return FILTERING_FAILURE_MARKER;
        }

    };

    /**
     * Cache of instances for request and response body filters.
     * Uses a concurrent map as an instance of this factory is typically shared across concurrently
     * processed requests.
     */
    protected final Map<Class<?>, LoggedBodyFilter> cache = new ConcurrentHashMap<>();

    /**
     * Creates a factory having instantiated no filter yet.
     */
    public LoggedBodyFilterFactory() {
        // Filters are instantiated on first use
    }

    /**
     * Gets the filter instances for the given filter classes, instantiating (and caching) any not
     * already resolved.
     *
     * @param filterTypes The filter classes to instantiate
     * @return The unmodifiable set of filter instances to be applied, iterating in the order the classes
     * were declared, so filters that depend on one another's output run predictably
     */
    public Set<LoggedBodyFilter> getInstances(Class<? extends LoggedBodyFilter>[] filterTypes) {
        return getInstances(asList(filterTypes));
    }

    /**
     * Gets the filter instances for the given filter classes, instantiating (and caching) any not
     * already resolved.
     *
     * @param filterTypes The filter classes to instantiate, in the order they must be applied
     * @return The unmodifiable set of filter instances to be applied, iterating in the order the classes
     * were declared, so filters that depend on one another's output run predictably
     */
    public Set<LoggedBodyFilter> getInstances(Collection<Class<? extends LoggedBodyFilter>> filterTypes) {
        if (filterTypes.isEmpty()) {
            return Set.of();
        }
        Set<LoggedBodyFilter> instances = new LinkedHashSet<>();
        filterTypes.forEach(type -> instances.add(getInstance(type)));
        return unmodifiableSet(instances);
    }

    /**
     * Gets (instantiating and caching if not already done) the instance for the given filter class.
     * <p>
     * A class that cannot be initialized fails with a {@link LinkageError} rather than an exception - an
     * {@link ExceptionInInitializerError} the first time, as when a pattern constant does not compile, and a
     * {@link NoClassDefFoundError} every time after - which is treated like any other failure to instantiate
     * the filter, rather than escaping every guard up to the exchange.
     *
     * @param type The body filter class to be instantiated
     * @param <T>  The body filter type
     * @return The instance created, or {@link #FAILED_BODY_FILTER} if it failed
     */
    protected <T extends LoggedBodyFilter> LoggedBodyFilter getInstance(Class<T> type) {
        return cache.computeIfAbsent(type, ignored -> {
            try {
                return type.getConstructor().newInstance();
            } catch (Exception | LinkageError e) {
                log.error("Unable to instantiate body filter {}, the bodies it applies to are dropped rather than logged unfiltered", type, e);
                return FAILED_BODY_FILTER;
            }
        });
    }

}
