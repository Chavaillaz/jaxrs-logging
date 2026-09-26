package com.chavaillaz.jakarta.rs;

import static jakarta.ws.rs.Priorities.ENTITY_CODER;
import static jakarta.ws.rs.Priorities.HEADER_DECORATOR;
import static jakarta.ws.rs.RuntimeType.SERVER;
import static jakarta.ws.rs.core.MediaType.WILDCARD_TYPE;
import static java.util.Objects.requireNonNull;

import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.chavaillaz.jakarta.rs.MethodFilter.BodyInterceptor;
import com.chavaillaz.jakarta.rs.client.LoggedClientFeature;
import com.chavaillaz.jakarta.rs.internal.BodyCapturer;
import com.chavaillaz.jakarta.rs.internal.LoggingGuard;

/**
 * Logs the requests of the resource methods carrying an annotation of this library - {@link Logged},
 * {@link LoggedBody} or {@link LoggedMapping}, wherever it is declared (see {@link Logged}) - describing each of
 * them in {@link MDC} for every line logged while it is processed (see {@link LoggedField}), and logging it once
 * answered as <code>Processed [method] [URI] with status [status] in [duration]ms</code>, at a level derived
 * from the status.
 * <p>
 * The annotations of each resource method are read once, as the application is deployed, and the methods
 * logged alone get the filter logging their requests: at {@link Priorities#HEADER_DECORATOR}, after the
 * authentication and authorization filters and before those of the application, so its {@link MDC} entries
 * cover the latter; with an interceptor capturing their bodies after any entity coder, for the methods logging
 * one, so the entity is captured rather than its transfer encoding.
 * <p>
 * It is configured through a {@code ContextResolver<LoggedFilterConfiguration>} of the application (see
 * {@link LoggedFilterConfiguration}), or constructed with its configuration by an application registering its
 * providers explicitly. Register a single one, configured rather than subclassed: RESTEasy and Jersey keep one
 * filter of a class per resource method, so a second feature, a subclass next to the one a container discovers,
 * would leave which of them logs a request to the order they are configured in.
 */
@Provider
@ConstrainedTo(SERVER)
public class LoggedFeature implements DynamicFeature {

    /**
     * Logger the requests, their bodies and the failures to log them are written to.
     */
    static final Logger log = LoggerFactory.getLogger(LoggedFeature.class);

    /**
     * Name of the header carrying the request identifier, read from the requests received and set by
     * {@link LoggedClientFeature} on the calls made, so both sides of a call are logged under one identifier.
     */
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    /**
     * Resolves the configuration of the resource methods, instantiating the body filters they name once.
     */
    private final LoggedResolver resolver = new LoggedResolver();

    /**
     * What this feature works with, made from its configuration once known: when constructed with one, and
     * when it logs its first request for one looked up.
     */
    private final AtomicReference<@Nullable Setup> setup = new AtomicReference<>();

    /**
     * Provides the context resolvers the configuration is looked up with, {@code null} for a runtime injecting
     * them into the filters this feature registers only, and outside a container.
     */
    @Context
    @Nullable Providers providers;

    /**
     * Creates a feature configured the way the application declares, through a provider of
     * {@code ContextResolver<LoggedFilterConfiguration>} asked for the class of the feature as the first
     * request is logged, and with the default configuration if it declares none (see
     * {@link LoggedFilterConfiguration#defaults()}).
     */
    public LoggedFeature() {
        // Configured as the first request is logged, see setup(Providers)
    }

    /**
     * Creates a feature with the given configuration, for an application registering its providers
     * explicitly: no configuration is looked up then.
     *
     * @param configuration The configuration of the feature
     */
    public LoggedFeature(LoggedFilterConfiguration configuration) {
        setup.set(Setup.of(requireNonNull(configuration, "The configuration is required")));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Registers the filter logging the requests of the given resource method if it carries an annotation of
     * this library, with the interceptor capturing its bodies if it logs any. The other methods get a filter
     * removing from the thread serving their requests what a request logged earlier may have left there, as
     * one completed on another thread does. A method whose annotations cannot be read is reported, and served
     * unlogged.
     */
    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        MethodFilter filter = LoggingGuard.safely(log, "Unable to configure the logging of a resource method, its requests are not logged", () -> {
            ResourceInfo resource = DeployedResource.of(resourceInfo);
            return resolver.isLogged(resource) ? filterFor(resource) : null;
        }, null);
        if (filter == null) {
            context.register(new SweepFilter(), HEADER_DECORATOR);
            return;
        }
        context.register(filter, HEADER_DECORATOR);
        if (filter.capturesBodies()) {
            // After the entity coders, which a lower priority runs inside
            context.register(new BodyInterceptor(filter), ENTITY_CODER + 100);
        }
    }

    /**
     * Creates the filter logging the requests of the given resource method, configured as its annotations say.
     *
     * @param resource The resource method and its class
     * @return The filter created
     */
    MethodFilter filterFor(ResourceInfo resource) {
        return new MethodFilter(this, resolver.resolve(DeployedResource.of(resource)));
    }

    /**
     * Gets what this feature works with, made from the configuration the application declares the first time
     * it is asked for, when the feature was not constructed with one.
     *
     * @param injected The context resolvers the runtime injected into the filter asking, to look the
     *                 configuration up with when it injected none into this feature
     * @return What this feature works with
     */
    Setup setup(@Nullable Providers injected) {
        Setup current = setup.get();
        if (current != null) {
            return current;
        }
        // Threads logging their first requests at once may each make one: they all go on with the first
        Setup made = Setup.of(lookUpConfiguration(providers != null ? providers : injected));
        return setup.compareAndSet(null, made) ? made : requireNonNull(setup.get());
    }

    /**
     * Looks up the configuration the application declares for this feature, falling back to the default one
     * when it declares none, or when looking it up fails, which is reported rather than allowed to fail a
     * request.
     *
     * @param available The context resolvers of the application, {@code null} if none were injected
     * @return The configuration of this feature
     */
    private LoggedFilterConfiguration lookUpConfiguration(@Nullable Providers available) {
        if (available == null) {
            return LoggedFilterConfiguration.defaults();
        }
        return LoggingGuard.safely(log, "Unable to look up the configuration of the application, the default one is used instead", () -> {
            ContextResolver<LoggedFilterConfiguration> resolver = available.getContextResolver(LoggedFilterConfiguration.class, WILDCARD_TYPE);
            LoggedFilterConfiguration configuration = resolver == null ? null : resolver.getContext(getClass());
            return configuration == null ? LoggedFilterConfiguration.defaults() : configuration;
        }, LoggedFilterConfiguration.defaults());
    }

    /**
     * What a feature works with, all of it made from its configuration.
     *
     * @param configuration  The configuration of the feature
     * @param mdc            The MDC entries of the requests the feature logs, named as configured
     * @param describer      Describes the requests received, masking the parameters the configuration reports
     * @param mappingApplier Puts the MDC entries the {@link LoggedMapping} annotations ask for
     * @param bodyCapturer   Captures the bodies read and written, as configured
     * @param exchangeLogger Writes the lines logging the requests, and returns their identifier to the caller
     */
    record Setup(LoggedFilterConfiguration configuration, RequestMdc mdc, RequestDescriber describer,
                 MappingApplier mappingApplier, BodyCapturer bodyCapturer, ExchangeLogger exchangeLogger) {

        /**
         * Makes what a feature configured as given works with.
         *
         * @param configuration The configuration of the feature
         * @return What the feature works with
         */
        static Setup of(LoggedFilterConfiguration configuration) {
            RequestMdc mdc = new RequestMdc(configuration.fieldNames());
            return new Setup(configuration, mdc,
                    new RequestDescriber(configuration::isSensitive),
                    new MappingApplier(configuration::isSensitive, mdc::isTaken),
                    new BodyCapturer(log, configuration::createBodyCapture),
                    new ExchangeLogger(log, configuration));
        }

    }

    /**
     * Resource method as the runtime deployed it, copied from the object describing it to the feature, which the
     * filters read on every request.
     *
     * @param resourceClass  The resource class, possibly {@code null}
     * @param resourceMethod The resource method, possibly {@code null}
     */
    private record DeployedResource(@Nullable Class<?> resourceClass, @Nullable Method resourceMethod) implements ResourceInfo {

        /**
         * Copies the resource method the given object describes.
         *
         * @param resourceInfo The resource method the runtime deploys
         * @return The resource method
         */
        static DeployedResource of(ResourceInfo resourceInfo) {
            return new DeployedResource(resourceInfo.getResourceClass(), resourceInfo.getResourceMethod());
        }

        @Override
        public @Nullable Class<?> getResourceClass() {
            return resourceClass;
        }

        @Override
        public @Nullable Method getResourceMethod() {
            return resourceMethod;
        }

        @Override
        public String toString() {
            return (resourceClass == null ? "?" : resourceClass.getName()) + "#" + (resourceMethod == null ? "?" : resourceMethod.getName());
        }

    }

    /**
     * Removes from the thread serving a request of a resource method no feature logs what a request logged
     * earlier may have left there (see {@link RequestMdc#sweep()}): one completed on another thread, or one
     * whose completion the runtime never reached, as Apache CXF answering an exception no mapper handles.
     */
    @ConstrainedTo(SERVER)
    private static final class SweepFilter implements ContainerRequestFilter {

        @Override
        public void filter(ContainerRequestContext requestContext) {
            LoggingGuard.safely(log, "Unable to sweep the entries a request left in MDC, the request itself is left unaffected", () -> {
                // Unless a filter logging the request started it, whose entries the thread now carries
                if (LoggedRequestState.find(requestContext) == null) {
                    RequestMdc.sweep();
                }
            });
        }

    }

}
