package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getAnnotations;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getDeclarationSites;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getMergedMappings;
import static com.chavaillaz.jakarta.rs.MappingApplier.inApplicationOrder;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toUnmodifiableSet;

import jakarta.ws.rs.container.ResourceInfo;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chavaillaz.jakarta.rs.LoggedBody.Direction;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyConfiguration;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyFilterFactory;

/**
 * Resolves whether the requests of a resource method are logged, and which {@link LoggedMapping} and
 * {@link LoggedBody} configuration applies to them, from the annotations of its declaration sites (see
 * {@link LoggedUtils#getDeclarationSites}).
 * <p>
 * {@link LoggedFeature} resolves each resource method once, as it is deployed. A failure to read the
 * annotations is reported then and leaves the method logging less rather than failing its requests: the
 * reflection it comes from would fail the same way on every request.
 */
final class MethodResolver {

    private static final Logger log = LoggerFactory.getLogger(MethodResolver.class);

    /**
     * Annotations of this library whose presence on a declaration site has the requests of the resource
     * method logged. The repeatable ones come with the annotation the compiler wraps them in once repeated.
     */
    private static final List<Class<? extends Annotation>> ACTIVATING =
            List.of(Logged.class, LoggedBody.class, LoggedMapping.class, LoggedMappings.class);

    /**
     * Instantiates the {@link LoggedBodyFilter} classes the annotations name, once per class.
     */
    private final LoggedBodyFilterFactory bodyFilterFactory;

    /**
     * Creates a resolver with its own body filter factory.
     */
    MethodResolver() {
        this(new LoggedBodyFilterFactory());
    }

    /**
     * Creates a resolver instantiating body filters through the given factory.
     *
     * @param bodyFilterFactory The factory to instantiate body filter classes with
     */
    MethodResolver(LoggedBodyFilterFactory bodyFilterFactory) {
        this.bodyFilterFactory = bodyFilterFactory;
    }

    /**
     * Indicates whether the requests of the given resource method are logged: whether one of its declaration
     * sites carries an annotation of this library. A method whose declaration sites cannot be walked is
     * reported, and not logged.
     *
     * @param resource The resource method and its class
     * @return {@code true} if the requests of the resource method are logged, {@code false} otherwise
     */
    boolean isLogged(ResourceInfo resource) {
        try {
            return getDeclarationSites(resource.getResourceClass(), resource.getResourceMethod()).stream()
                    .anyMatch(site -> ACTIVATING.stream().anyMatch(site::isAnnotationPresent));
        } catch (RuntimeException e) {
            log.error("Unable to find out whether the requests of {} are logged, they are not", resource, e);
            return false;
        }
    }

    /**
     * Resolves the configuration of the given resource method, each part of it falling back to logging
     * nothing when it cannot be resolved:
     * <ul>
     *     <li>its mappings, merged from every declaration site (see {@link LoggedUtils#getMergedMappings}),
     *     in the order they apply in (see {@link MappingApplier#inApplicationOrder});</li>
     *     <li>its body logging, no body at all when it cannot be resolved: a {@link LoggedBody#filters()}
     *     naming a class missing at runtime throws a {@link TypeNotPresentException} once read, and a body
     *     whose filters are unknown is one whose redaction cannot be guaranteed.</li>
     * </ul>
     *
     * @param resource The resource method and its class
     * @return The configuration of the resource method
     */
    MethodConfiguration resolve(ResourceInfo resource) {
        List<LoggedMapping> mappings;
        try {
            mappings = inApplicationOrder(getMergedMappings(resource));
        } catch (RuntimeException e) {
            log.error("Unable to resolve the MDC mappings of {}, none of them is applied", resource, e);
            mappings = List.of();
        }
        try {
            return new MethodConfiguration(resource, mappings, resolve(resource, REQUEST), resolve(resource, RESPONSE));
        } catch (RuntimeException e) {
            log.error("Unable to resolve the body logging configuration of {}, no body of it is logged", resource, e);
            return new MethodConfiguration(resource, mappings, LoggedBodyConfiguration.NONE, LoggedBodyConfiguration.NONE);
        }
    }

    /**
     * Resolves the body logging configuration applying to the given direction into its ready-to-use form.
     *
     * @param resource  The resource method and its class
     * @param direction The direction to resolve the configuration of
     * @return The body logging configuration, {@link LoggedBodyConfiguration#NONE} if none applies
     */
    private LoggedBodyConfiguration resolve(ResourceInfo resource, Direction direction) {
        return findAnnotation(resource, direction)
                .map(annotation -> new LoggedBodyConfiguration(
                        stream(annotation.value()).collect(toUnmodifiableSet()),
                        annotation.limit(),
                        bodyFilterFactory.getInstances(annotation.filters())))
                .orElse(LoggedBodyConfiguration.NONE);
    }

    /**
     * Finds the {@link LoggedBody} annotation applying to the given direction: one applying to it alone wins
     * over one applying to both, and among several as specific, the first one declared.
     *
     * @param resource  The resource method and its class
     * @param direction The direction to find the configuration of
     * @return The body logging annotation applying, if any
     */
    private Optional<LoggedBody> findAnnotation(ResourceInfo resource, Direction direction) {
        LoggedBody both = null;
        for (LoggedBody logging : getAnnotations(resource, LoggedBody.class)) {
            Set<Direction> directions = EnumSet.noneOf(Direction.class);
            Collections.addAll(directions, logging.directions());
            if (directions.equals(EnumSet.of(direction))) {
                return Optional.of(logging);
            } else if (both == null && directions.equals(EnumSet.allOf(Direction.class))) {
                both = logging;
            }
        }
        return Optional.ofNullable(both);
    }

    /**
     * Configuration of the logging of a resource method, resolved as it is deployed.
     *
     * @param resource     The resource method and its class
     * @param mappings     The mappings applying to its requests, in the order they apply in
     * @param requestBody  The body logging configuration of its requests
     * @param responseBody The body logging configuration of its responses
     */
    record MethodConfiguration(ResourceInfo resource, List<LoggedMapping> mappings,
                               LoggedBodyConfiguration requestBody, LoggedBodyConfiguration responseBody) {

        /**
         * Gets the body logging configuration of the given direction.
         *
         * @param direction The direction
         * @return The body logging configuration, never {@code null}
         */
        LoggedBodyConfiguration body(Direction direction) {
            return direction == REQUEST ? requestBody : responseBody;
        }

        /**
         * Indicates whether a body is captured in either direction.
         *
         * @return {@code true} if a body is captured, {@code false} otherwise
         */
        boolean capturesBodies() {
            return requestBody.isActive() || responseBody.isActive();
        }

    }

}
