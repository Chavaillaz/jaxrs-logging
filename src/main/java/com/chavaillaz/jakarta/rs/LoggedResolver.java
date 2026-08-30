package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getAnnotation;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.chavaillaz.jakarta.rs.LoggedBody.Direction;
import jakarta.ws.rs.container.ResourceInfo;

/**
 * Resolves which {@link LoggedMapping} and {@link LoggedBody} configuration applies to a given resource
 * method, by walking the annotations present on its class, its interfaces and the method itself.
 * <p>
 * Resolution only depends on the (immutable) annotations present on a resource method, not on any
 * request data, so results are cached per {@link Method} once resolved, avoiding a reflection-based
 * annotation lookup on every single request to the same resource method.
 * <p>
 * The caches below are never evicted, which assumes a bounded, stable set of resource methods, as is the
 * case for a typical application with a fixed set of JAX-RS endpoints; this resolver is not suited to
 * applications that generate new resource classes at runtime (e.g. per-tenant code generation).
 * <p>
 * Deliberately independent of any request-scoped state (a {@link ResourceInfo} is taken as a parameter
 * rather than injected as a field), so resolution can be unit-tested, or reused from another provider,
 * without needing to mock a whole request/response context.
 */
public class LoggedResolver {

    /**
     * Body logging configuration resolved for both directions of a given resource method.
     *
     * @param request  The body logging configuration applicable to the request, if any
     * @param response The body logging configuration applicable to the response, if any
     */
    private record BodyConfiguration(Optional<LoggedBody> request, Optional<LoggedBody> response) {

    }

    /**
     * Cache of the merged {@link LoggedMapping} definitions resolved for each resource method.
     */
    protected final Map<Method, Set<LoggedMapping>> mappingsCache = new ConcurrentHashMap<>();

    /**
     * Cache of the body logging configuration resolved for each resource method.
     */
    protected final Map<Method, BodyConfiguration> bodyConfigurationCache = new ConcurrentHashMap<>();

    /**
     * Gets the merged {@link LoggedMapping} definitions applicable to the resource method matched by
     * the given resource, resolving and caching them once per resource method.
     *
     * @param resourceInfo The instance to access resource class and method
     * @return The set of merged mappings applicable to the resource method
     */
    public Set<LoggedMapping> getMergedMappings(ResourceInfo resourceInfo) {
        return mappingsCache.computeIfAbsent(resourceInfo.getResourceMethod(),
                method -> LoggedUtils.getMergedMappings(resourceInfo));
    }

    /**
     * Gets the most specific body logging configuration for the given target (request or response) of
     * the resource method matched by the given resource.
     * If multiple configurations are defined, the one specifically targeting the given target is returned.
     * Otherwise, the configuration targeting both request and response is returned if present.
     * <p>
     * Both directions are resolved and cached together the first time either is requested for a given
     * resource method, as reflection-based annotation lookups are expensive to repeat on every request.
     *
     * @param resourceInfo The instance to access resource class and method
     * @param target       The target for which to find the body logging configuration
     * @return The most specific body logging configuration if present
     */
    public Optional<LoggedBody> getBodyConfiguration(ResourceInfo resourceInfo, Direction target) {
        BodyConfiguration configuration = bodyConfigurationCache.computeIfAbsent(resourceInfo.getResourceMethod(),
                method -> new BodyConfiguration(
                        resolveBodyConfiguration(resourceInfo, REQUEST),
                        resolveBodyConfiguration(resourceInfo, RESPONSE)));
        return target == REQUEST ? configuration.request() : configuration.response();
    }

    /**
     * Finds the most specific body logging configuration for the given target (request or response)
     * by walking the annotations present on the resource class/method matched by the given resource.
     *
     * @param resourceInfo The instance to access resource class and method
     * @param target       The target for which to find the body logging configuration
     * @return The most specific body logging configuration if present
     */
    protected Optional<LoggedBody> resolveBodyConfiguration(ResourceInfo resourceInfo, Direction target) {
        LoggedBody both = null;
        for (LoggedBody logging : getAnnotation(resourceInfo, LoggedBody.class, Logged.class, Logged::value)) {
            List<Direction> targets = Arrays.asList(logging.targets());
            if (targets.size() == 1 && targets.getFirst() == target) {
                return Optional.of(logging);
            }
            if (targets.size() == 2 && targets.contains(REQUEST) && targets.contains(RESPONSE)) {
                both = logging;
            }
        }
        return Optional.ofNullable(both);
    }

}
