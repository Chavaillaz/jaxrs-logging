package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture.FILTERING_FAILURE_MARKER;
import static java.util.Arrays.asList;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chavaillaz.jakarta.rs.LoggedFeature;
import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;
import com.chavaillaz.jakarta.rs.client.LoggedClientFeature;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Instantiates the {@link LoggedBodyFilter} classes, once per class, for {@link LoggedFeature} and
 * {@link LoggedClientFeature} alike, a filter being stateless and thread-safe.
 */
public class LoggedBodyFilterFactory {

    /**
     * Logger reporting a body filter class that cannot be instantiated.
     */
    protected static final Logger log = LoggerFactory.getLogger(LoggedBodyFilterFactory.class);

    /**
     * Filter cached for a class that failed to be instantiated, reported once: it drops the body, writing
     * {@link BoundedLoggedBodyCapture#FILTERING_FAILURE_MARKER} in its place, as a filter that could not be
     * created redacted nothing - a constructor that is not public would otherwise have every payload it
     * protects logged in the clear.
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
     * Filters instantiated, by class.
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
     * @return The unmodifiable list of filter instances, as {@link #getInstances(Collection)} returns it
     */
    public List<LoggedBodyFilter> getInstances(Class<? extends LoggedBodyFilter>[] filterTypes) {
        return getInstances(asList(filterTypes));
    }

    /**
     * Gets the filter instances for the given filter classes, instantiating (and caching) any not
     * already resolved.
     *
     * @param filterTypes The filter classes to instantiate, in the order they must be applied
     * @return The unmodifiable list of filter instances, in the order the classes were declared, so filters
     *         depending on one another's output run predictably, a class declared twice applied once
     */
    public List<LoggedBodyFilter> getInstances(Collection<Class<? extends LoggedBodyFilter>> filterTypes) {
        if (filterTypes.isEmpty()) {
            return List.of();
        }
        Set<LoggedBodyFilter> instances = new LinkedHashSet<>();
        filterTypes.forEach(type -> instances.add(getInstance(type)));
        return List.copyOf(instances);
    }

    /**
     * Gets the instance of the given filter class, instantiated once. A class that cannot be initialized, failing
     * with a {@link LinkageError}, is treated as any other failure to instantiate it.
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
