package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getAnnotation;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toUnmodifiableSet;

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
 * request data, so results are cached once resolved, avoiding a reflection-based annotation lookup on
 * every single request to the same resource method. Body configurations are moreover cached in their
 * fully resolved form ({@link LoggedBodyConfiguration}, holding a {@link Set} of log types and already
 * instantiated {@link LoggedBodyFilter}s) rather than as the raw annotation, so a request costs one map
 * lookup instead of rebuilding those collections.
 * <p>
 * The cache is keyed on the resource <em>class and method</em> together, not on the method alone: the
 * same {@link Method} object can be matched for two different resource classes (a container handing out
 * the interface method for several implementations of a shared JAX-RS interface), and resolution walks
 * the resource class too, so keying on the method alone would serve one class's configuration for another.
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
     * Key identifying the resource a configuration was resolved for.
     *
     * @param resourceClass  The resource class matched by the request
     * @param resourceMethod The resource method matched by the request
     */
    protected record ResourceKey(Class<?> resourceClass, Method resourceMethod) {

        /**
         * Creates the key for the given resource, or {@code null} when there is nothing to key on.
         *
         * @param resourceInfo The instance to access resource class and method
         * @return The key, or {@code null} if the container resolved neither a class nor a method
         */
        static ResourceKey of(ResourceInfo resourceInfo) {
            Class<?> resourceClass = resourceInfo.getResourceClass();
            Method resourceMethod = resourceInfo.getResourceMethod();
            // Neither is guaranteed to be resolved at this stage by every container, and a
            // ConcurrentHashMap forbids a null key, so an unusable key is reported as such
            return resourceClass == null && resourceMethod == null
                    ? null
                    : new ResourceKey(resourceClass, resourceMethod);
        }

    }

    /**
     * Body logging configuration resolved for both directions of a given resource method.
     *
     * @param request  The body logging configuration applicable to the request
     * @param response The body logging configuration applicable to the response
     */
    protected record BodyConfiguration(LoggedBodyConfiguration request, LoggedBodyConfiguration response) {

        static final BodyConfiguration NONE = new BodyConfiguration(LoggedBodyConfiguration.NONE, LoggedBodyConfiguration.NONE);

    }

    /**
     * Cache of the merged {@link LoggedMapping} definitions resolved for each resource.
     */
    protected final Map<ResourceKey, Set<LoggedMapping>> mappingsCache = new ConcurrentHashMap<>();

    /**
     * Cache of the body logging configuration resolved for each resource.
     */
    protected final Map<ResourceKey, BodyConfiguration> bodyConfigurationCache = new ConcurrentHashMap<>();

    /**
     * Instantiates and caches the {@link LoggedBodyFilter} classes referenced by resolved annotations.
     */
    protected final LoggedBodyFilterFactory bodyFilterFactory;

    /**
     * Creates a resolver with its own body filter factory.
     */
    public LoggedResolver() {
        this(new LoggedBodyFilterFactory());
    }

    /**
     * Creates a resolver instantiating body filters through the given factory, so a provider already
     * owning one (see {@link LoggedFilter#bodyFilterFactory}) shares its instances with this resolver.
     *
     * @param bodyFilterFactory The factory to instantiate body filter classes with
     */
    public LoggedResolver(LoggedBodyFilterFactory bodyFilterFactory) {
        this.bodyFilterFactory = bodyFilterFactory;
    }

    /**
     * Gets the merged {@link LoggedMapping} definitions applicable to the resource method matched by
     * the given resource, resolving and caching them once per resource.
     *
     * @param resourceInfo The instance to access resource class and method
     * @return The set of merged mappings applicable to the resource method
     */
    public Set<LoggedMapping> getMergedMappings(ResourceInfo resourceInfo) {
        ResourceKey key = ResourceKey.of(resourceInfo);
        if (key == null) {
            return Set.of();
        }
        return mappingsCache.computeIfAbsent(key, ignored -> LoggedUtils.getMergedMappings(resourceInfo));
    }

    /**
     * Gets the body logging configuration for the given target (request or response) of the resource
     * method matched by the given resource.
     * If multiple configurations are defined, the one specifically targeting the given target is used.
     * Otherwise, the configuration targeting both request and response is used if present.
     * <p>
     * Both directions are resolved and cached together the first time either is requested for a given
     * resource, as reflection-based annotation lookups are expensive to repeat on every request.
     *
     * @param resourceInfo The instance to access resource class and method
     * @param target       The target for which to find the body logging configuration
     * @return The body logging configuration, or {@link LoggedBodyConfiguration#NONE} if none applies
     */
    public LoggedBodyConfiguration getBodyConfiguration(ResourceInfo resourceInfo, Direction target) {
        ResourceKey key = ResourceKey.of(resourceInfo);
        BodyConfiguration configuration = key == null
                ? BodyConfiguration.NONE
                : bodyConfigurationCache.computeIfAbsent(key, ignored -> new BodyConfiguration(
                        resolve(resourceInfo, REQUEST),
                        resolve(resourceInfo, RESPONSE)));
        return target == REQUEST ? configuration.request() : configuration.response();
    }

    /**
     * Resolves the body logging configuration applicable to the given target into its ready-to-use form.
     *
     * @param resourceInfo The instance to access resource class and method
     * @param target       The target for which to resolve the body logging configuration
     * @return The body logging configuration, or {@link LoggedBodyConfiguration#NONE} if none applies
     */
    protected LoggedBodyConfiguration resolve(ResourceInfo resourceInfo, Direction target) {
        return findAnnotation(resourceInfo, target)
                .map(annotation -> new LoggedBodyConfiguration(
                        stream(annotation.value()).collect(toUnmodifiableSet()),
                        annotation.limit(),
                        bodyFilterFactory.getInstances(annotation.filters())))
                .orElse(LoggedBodyConfiguration.NONE);
    }

    /**
     * Finds the most specific {@link LoggedBody} annotation for the given target (request or response)
     * by walking the annotations present on the resource class/method matched by the given resource.
     *
     * @param resourceInfo The instance to access resource class and method
     * @param target       The target for which to find the body logging configuration
     * @return The most specific body logging annotation if present
     */
    protected Optional<LoggedBody> findAnnotation(ResourceInfo resourceInfo, Direction target) {
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
