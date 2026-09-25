package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getAnnotation;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toUnmodifiableSet;

import jakarta.ws.rs.container.ResourceInfo;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chavaillaz.jakarta.rs.LoggedBody.Direction;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyConfiguration;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyFilterFactory;

/**
 * Resolves which {@link LoggedMapping} and {@link LoggedBody} configuration applies to a given resource
 * method, by walking the annotations present on its class, its interfaces and the method itself.
 * <p>
 * Resolution only depends on those annotations, so it is cached - body configurations in their ready-to-use
 * form ({@link LoggedBodyConfiguration}, filters instantiated) - keyed on the resource class and method
 * together, as the same {@link Method} of a shared interface can be matched for several resource classes.
 * The caches are never evicted, which suits the fixed set of resources of an application but not one
 * generating resource classes at runtime.
 * <p>
 * A {@link ResourceInfo} is taken as a parameter rather than injected, so resolution depends on no request.
 */
final class LoggedResolver {

    /**
     * Logger reporting a configuration that cannot be resolved.
     */
    private static final Logger log = LoggerFactory.getLogger(LoggedResolver.class);

    /**
     * Key identifying the resource a configuration was resolved for.
     *
     * @param resourceClass  The resource class matched by the request, possibly {@code null}
     * @param resourceMethod The resource method matched by the request, possibly {@code null}
     */
    record ResourceKey(@Nullable Class<?> resourceClass, @Nullable Method resourceMethod) {

        /**
         * Creates the key for the given resource, or {@code null} when there is nothing to key on.
         *
         * @param resourceInfo The instance to access resource class and method
         * @return The key, or {@code null} if the container resolved neither a class nor a method
         */
        static @Nullable ResourceKey of(ResourceInfo resourceInfo) {
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
     * <p>
     * Both directions travel together so a caller needing them repeatedly (as a filter does, several
     * times per request) can hold on to a single, already resolved value rather than asking for one
     * direction at a time.
     *
     * @param request  The body logging configuration applicable to the request
     * @param response The body logging configuration applicable to the response
     */
    record BodyConfiguration(LoggedBodyConfiguration request, LoggedBodyConfiguration response) {

        /**
         * Configuration logging nothing in either direction, used whenever no {@link LoggedBody} applies.
         */
        static final BodyConfiguration NONE = new BodyConfiguration(LoggedBodyConfiguration.NONE, LoggedBodyConfiguration.NONE);

        /**
         * Gets the configuration applicable to the given direction.
         *
         * @param target The direction to get the configuration of
         * @return The body logging configuration, never {@code null}
         */
        LoggedBodyConfiguration of(Direction target) {
            return target == REQUEST ? request : response;
        }

    }

    /**
     * Cache of the {@link LoggedMapping} definitions resolved for each resource, in the order they apply in.
     */
    final Map<ResourceKey, List<LoggedMapping>> mappingsCache = new ConcurrentHashMap<>();

    /**
     * Cache of the body logging configuration resolved for each resource.
     */
    final Map<ResourceKey, BodyConfiguration> bodyConfigurationCache = new ConcurrentHashMap<>();

    /**
     * Instantiates and caches the {@link LoggedBodyFilter} classes referenced by resolved annotations.
     */
    private final LoggedBodyFilterFactory bodyFilterFactory;

    /**
     * Creates a resolver with its own body filter factory.
     */
    LoggedResolver() {
        this(new LoggedBodyFilterFactory());
    }

    /**
     * Creates a resolver instantiating body filters through the given factory, so a provider already
     * owning one (see {@link LoggedFilter#bodyFilterFactory}) shares its instances with this resolver.
     *
     * @param bodyFilterFactory The factory to instantiate body filter classes with
     */
    LoggedResolver(LoggedBodyFilterFactory bodyFilterFactory) {
        this.bodyFilterFactory = bodyFilterFactory;
    }

    /**
     * Gets the {@link LoggedMapping} definitions applicable to the resource method matched by the given
     * resource, resolving and caching them once per resource.
     * <p>
     * The mappings declared on the several declaration sites of the method are merged (see
     * {@link LoggedUtils#getMergedMappings(ResourceInfo)}), then sorted in the order they apply in: the
     * explicit ones before the automatic ones, and on their MDC key within each, which puts the exclusions
     * - declaring none - first. Sorting is part of the resolution, done once per resource rather than on
     * every request.
     *
     * @param resourceInfo The instance to access resource class and method
     * @return The mappings applicable to the resource method, in the order they apply in
     */
    List<LoggedMapping> getMappings(ResourceInfo resourceInfo) {
        ResourceKey key = ResourceKey.of(resourceInfo);
        if (key == null) {
            return List.of();
        }
        return mappingsCache.computeIfAbsent(key, ignored -> MappingApplier.inApplicationOrder(LoggedUtils.getMergedMappings(resourceInfo)));
    }

    /**
     * Gets the body logging configuration for the given target (request or response) of the resource
     * method matched by the given resource.
     * If multiple configurations are defined, the one specifically targeting the given target is used.
     * Otherwise, the configuration targeting both request and response is used if present. Among several
     * as specific as one another, the first one declared is used (see {@link #findAnnotation}).
     * <p>
     * Both directions are resolved and cached together the first time either is requested for a given
     * resource, as reflection-based annotation lookups are expensive to repeat on every request.
     *
     * @param resourceInfo The instance to access resource class and method
     * @param target       The target for which to find the body logging configuration
     * @return The body logging configuration, or {@link LoggedBodyConfiguration#NONE} if none applies
     */
    LoggedBodyConfiguration getBodyConfiguration(ResourceInfo resourceInfo, Direction target) {
        return getBodyConfiguration(resourceInfo).of(target);
    }

    /**
     * Gets the body logging configuration for both directions of the resource method matched by the
     * given resource, resolving and caching them together the first time either is requested.
     * <p>
     * Preferred by a caller needing the configuration more than once for the same request, which can then
     * look it up - and call the (usually proxied) {@link ResourceInfo} - once rather than per direction.
     *
     * @param resourceInfo The instance to access resource class and method
     * @return The body logging configuration of both directions, never {@code null}
     */
    BodyConfiguration getBodyConfiguration(ResourceInfo resourceInfo) {
        ResourceKey key = ResourceKey.of(resourceInfo);
        return key == null
                ? BodyConfiguration.NONE
                : bodyConfigurationCache.computeIfAbsent(key, ignored -> resolve(key, resourceInfo));
    }

    /**
     * Resolves the body logging configuration of both directions of the given resource into its
     * ready-to-use form, or into {@link BodyConfiguration#NONE} if it cannot be.
     * <p>
     * Resolving can fail on an annotation the application compiled against but cannot load at runtime - a
     * {@link LoggedBody#filters()} naming a class missing from the deployment throws a
     * {@link TypeNotPresentException} the moment it is read - which no later request will resolve any
     * better. Such a failure is therefore reported once and remembered, the way a filter class that cannot
     * be instantiated is (see {@link LoggedBodyFilterFactory}), and the resource logs no body at all: a body
     * whose filters cannot even be determined is one whose redaction cannot be guaranteed.
     *
     * @param key          The key identifying the resource, for the report of a failure
     * @param resourceInfo The instance to access resource class and method
     * @return The body logging configuration, or {@link BodyConfiguration#NONE} if it cannot be resolved
     */
    private BodyConfiguration resolve(ResourceKey key, ResourceInfo resourceInfo) {
        try {
            return new BodyConfiguration(resolve(resourceInfo, REQUEST), resolve(resourceInfo, RESPONSE));
        } catch (RuntimeException e) {
            log.error("Unable to resolve the body logging configuration of {}, no body of it is logged", key, e);
            return BodyConfiguration.NONE;
        }
    }

    /**
     * Resolves the body logging configuration applicable to the given target into its ready-to-use form.
     *
     * @param resourceInfo The instance to access resource class and method
     * @param target       The target for which to resolve the body logging configuration
     * @return The body logging configuration, or {@link LoggedBodyConfiguration#NONE} if none applies
     */
    private LoggedBodyConfiguration resolve(ResourceInfo resourceInfo, Direction target) {
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
     * <p>
     * An annotation targeting the given direction alone wins over one targeting both, and among several
     * as specific as one another, the first one declared wins. The directions an annotation targets are
     * read as a set, so naming one twice changes nothing.
     *
     * @param resourceInfo The instance to access resource class and method
     * @param target       The target for which to find the body logging configuration
     * @return The most specific body logging annotation if present
     */
    private Optional<LoggedBody> findAnnotation(ResourceInfo resourceInfo, Direction target) {
        LoggedBody both = null;
        for (LoggedBody logging : getAnnotation(resourceInfo, LoggedBody.class, Logged.class, Logged::value)) {
            Set<Direction> targets = EnumSet.noneOf(Direction.class);
            Collections.addAll(targets, logging.targets());
            if (targets.equals(EnumSet.of(target))) {
                return Optional.of(logging);
            } else if (both == null && targets.equals(EnumSet.allOf(Direction.class))) {
                both = logging;
            }
        }
        return Optional.ofNullable(both);
    }

}
