package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static jakarta.ws.rs.RuntimeType.CLIENT;
import static java.lang.System.nanoTime;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNullElseGet;
import static java.util.UUID.randomUUID;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.annotation.Priority;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.apache.commons.io.input.TeeInputStream;
import org.apache.commons.io.output.TeeOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

/**
 * Client-side counterpart of {@link LoggedFilter}, logging outgoing JAX-RS Client calls and
 * propagating the current request identifier (see {@link LoggedField#REQUEST_ID}) to the downstream
 * service, so a client calling another service exposing its own {@link LoggedFilter} produces a single,
 * correlated identifier across both sides of the call.
 * <p>
 * Unlike {@link LoggedFilter}, this provider has no resource method (and therefore no {@link Logged} /
 * {@link LoggedBody} annotations) to resolve configuration from: an instance is configured once, through
 * {@link #builder()}, and applies uniformly to every call made through the {@code Client}/{@code WebTarget}
 * it is registered on. Register a differently configured instance per target for different needs.
 * <p>
 * Logs the following lines:
 * <ul>
 *     <li>{@code Calling [method] [uri]}, once the request is about to be sent</li>
 *     <li>{@code Called [method] [uri] with status [status] in [duration]ms}, once the response is
 *     received - unconditionally, exactly once per call, regardless of body logging</li>
 * </ul>
 * If body logging is enabled, the request/response body is logged as a further, separate line rather
 * than being merged into the lines above (unlike {@link LoggedFilter}): the response body is only
 * available if/when the calling code actually reads the response entity (see
 * {@link #captureResponseBody(ReaderInterceptorContext)}, mirroring the equivalent, well-known limitation
 * on {@link LoggedFilter#aroundReadFrom} for the request body), which can happen after - or not at all
 * after - the "Called ..." line above already logged, so there is no single point at which both the
 * status line and the body are guaranteed to be available together to merge into one line the way the
 * server-side filter does.
 * <p>
 * Only {@link com.chavaillaz.jakarta.rs.LoggedBody.LogType#LOG} is supported (a separate log line), not
 * {@link com.chavaillaz.jakarta.rs.LoggedBody.LogType#MDC}: the server-side filter can attach the body to
 * MDC because it owns a single, well-defined completion point for the whole request ({@code logResponse},
 * see its Javadoc); this provider deliberately does not reproduce that coordination (for the reason above),
 * so there is no single point to scope such an MDC entry to.
 */
@Provider
@ConstrainedTo(CLIENT)
@Priority(Priorities.HEADER_DECORATOR)
public class LoggedClientFilter implements ClientRequestFilter, ClientResponseFilter, Feature {

    protected static final Logger log = LoggerFactory.getLogger(LoggedClientFilter.class);

    /**
     * Name of the property stored in request context to compute the duration time.
     */
    protected static final String REQUEST_TIME_PROPERTY = LoggedClientFilter.class.getName() + ".requestTime";

    /**
     * Name of the property stored in request context to retrieve the request method when logging the
     * request body from {@link #captureRequestBody(WriterInterceptorContext)}, which has no direct
     * access to the {@link ClientRequestContext} the method was read from.
     */
    protected static final String REQUEST_METHOD_PROPERTY = LoggedClientFilter.class.getName() + ".requestMethod";

    /**
     * Name of the property stored in request context to retrieve the request URI, for the same reason
     * as {@link #REQUEST_METHOD_PROPERTY}.
     */
    protected static final String REQUEST_URI_PROPERTY = LoggedClientFilter.class.getName() + ".requestUri";

    protected final LoggedBodyFilterFactory bodyFilterFactory = new LoggedBodyFilterFactory();

    protected final String correlationIdMdcKey;
    protected final boolean logRequestBody;
    protected final boolean logResponseBody;
    protected final int requestBodyLimit;
    protected final int responseBodyLimit;

    /**
     * Body filter instances, resolved once here rather than on every call: unlike {@link LoggedFilter},
     * whose configuration depends on the resource method matched by each request, this provider's
     * configuration is fixed at build time, so there is nothing about it left to resolve per call.
     */
    protected final Set<LoggedBodyFilter> bodyFilters;

    /**
     * Creates a new client filter with the default configuration (no body logging, correlation identifier
     * read from the {@code request-id} MDC key). Use {@link #builder()} to customize it.
     */
    public LoggedClientFilter() {
        this(builder());
    }

    protected LoggedClientFilter(Builder builder) {
        this.correlationIdMdcKey = builder.correlationIdMdcKey;
        this.logRequestBody = builder.logRequestBody;
        this.logResponseBody = builder.logResponseBody;
        this.requestBodyLimit = builder.requestBodyLimit;
        this.responseBodyLimit = builder.responseBodyLimit;
        // Classes first, then instances, keeping the declaration order within each: a filter given as a
        // class cannot depend on one given as an instance without the caller having built both anyway
        Set<LoggedBodyFilter> filters = new LinkedHashSet<>(bodyFilterFactory.getInstances(builder.bodyFilterClasses));
        filters.addAll(builder.bodyFilterInstances);
        this.bodyFilters = unmodifiableSet(filters);
    }

    /**
     * Creates a new builder to configure a {@link LoggedClientFilter} instance.
     *
     * @return The builder created
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link LoggedClientFilter}.
     */
    public static class Builder {

        private String correlationIdMdcKey = LoggedField.REQUEST_ID.getDefaultField();
        private boolean logRequestBody = false;
        private boolean logResponseBody = false;
        private int requestBodyLimit = -1;
        private int responseBodyLimit = -1;
        private final Set<Class<? extends LoggedBodyFilter>> bodyFilterClasses = new LinkedHashSet<>();
        private final Set<LoggedBodyFilter> bodyFilterInstances = new LinkedHashSet<>();

        /**
         * Sets the MDC key read to obtain the identifier propagated as {@value LoggedFilter#REQUEST_ID_HEADER}
         * on outgoing requests, falling back to a random one when absent from MDC (e.g. no {@link LoggedFilter}
         * is active on the calling thread). Defaults to {@code request-id}; change it to match a renamed
         * {@link LoggedField#REQUEST_ID} MDC key (see {@link LoggedFilter#mdcFields}).
         *
         * @param mdcKey The MDC key to read the correlation identifier from
         * @return This builder
         */
        public Builder correlationIdKey(String mdcKey) {
            this.correlationIdMdcKey = mdcKey;
            return this;
        }

        /**
         * Activates logging of the request body as a separate log line.
         *
         * @return This builder
         */
        public Builder logRequestBody() {
            this.logRequestBody = true;
            return this;
        }

        /**
         * Activates logging of the response body as a separate log line, if/when the calling code reads
         * the response entity (see the class Javadoc).
         *
         * @return This builder
         */
        public Builder logResponseBody() {
            this.logResponseBody = true;
            return this;
        }

        /**
         * Sets the size limit in bytes applied to both the request and response body when logged.
         *
         * @param limit The maximum size of the body to be logged in bytes, or {@code -1} for no limit
         * @return This builder
         */
        public Builder bodyLimit(int limit) {
            this.requestBodyLimit = limit;
            this.responseBodyLimit = limit;
            return this;
        }

        /**
         * Sets the size limit in bytes applied to the request body when logged.
         *
         * @param limit The maximum size of the body to be logged in bytes, or {@code -1} for no limit
         * @return This builder
         */
        public Builder requestBodyLimit(int limit) {
            this.requestBodyLimit = limit;
            return this;
        }

        /**
         * Sets the size limit in bytes applied to the response body when logged.
         *
         * @param limit The maximum size of the body to be logged in bytes, or {@code -1} for no limit
         * @return This builder
         */
        public Builder responseBodyLimit(int limit) {
            this.responseBodyLimit = limit;
            return this;
        }

        /**
         * Adds filters to be applied (both directions) before logging a body, in the given order (and in
         * declaration order across multiple calls), so filters that depend on one another's output run
         * predictably.
         *
         * @param filters The filter classes to add
         * @return This builder
         */
        @SafeVarargs
        public final Builder bodyFilters(Class<? extends LoggedBodyFilter>... filters) {
            this.bodyFilterClasses.addAll(Arrays.asList(filters));
            return this;
        }

        /**
         * Adds already built filters to be applied (both directions) before logging a body, in the given
         * order (and in declaration order across multiple calls), so filters that depend on one another's
         * output run predictably.
         * <p>
         * Unlike the overload taking classes, which mirrors what {@link LoggedBody#filters()} can express
         * and therefore needs each filter to be instantiable without arguments, this one takes instances:
         * a configured built-in filter such as
         * {@code new JsonMaskingBodyFilter("password")} can be passed straight in, without a subclass
         * declared only to fix its arguments.
         *
         * @param filters The filter instances to add
         * @return This builder
         */
        public Builder bodyFilters(LoggedBodyFilter... filters) {
            this.bodyFilterInstances.addAll(Arrays.asList(filters));
            return this;
        }

        /**
         * Builds the {@link LoggedClientFilter} configured by this builder.
         *
         * @return The client filter created
         */
        public LoggedClientFilter build() {
            return new LoggedClientFilter(this);
        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * Guarded like everything else this provider does (see {@link LoggedSupport#safely}): a call is not
     * worth failing over the line announcing it, over the identifier correlating it with the service it
     * reaches, or over a {@code Client} implementation returning something unexpected about the request
     * it is about to send. A call this provider could not describe is a call still made, only logged
     * with less.
     */
    @Override
    public void filter(ClientRequestContext requestContext) {
        safely(() -> {
            requestContext.setProperty(REQUEST_TIME_PROPERTY, nanoTime());
            requestContext.setProperty(REQUEST_METHOD_PROPERTY, requestContext.getMethod());
            requestContext.setProperty(REQUEST_URI_PROPERTY, requestContext.getUri().toString());

            // HTTP header names are case-insensitive, but the client-side header map is not guaranteed to be
            // (it is a plain MultivaluedMap in the JAX-RS Client API), so a caller having already set the
            // header under a different casing would otherwise get it sent twice with two different values
            if (requestContext.getHeaders().keySet().stream().noneMatch(REQUEST_ID_HEADER::equalsIgnoreCase)) {
                String correlationId = requireNonNullElseGet(MDC.get(correlationIdMdcKey), () -> randomUUID().toString());
                requestContext.getHeaders().putSingle(REQUEST_ID_HEADER, correlationId);
            }

            log.info("Calling {} {}", requestContext.getMethod(), requestContext.getUri());
        });
    }

    /**
     * Captures and logs the request body while the entity is being written.
     * <p>
     * Note that this is only invoked when the request actually has an entity to write, which is the
     * common case for a client call built with an entity (e.g. {@code target.request().post(entity)}).
     *
     * @param context The context of the entity being written
     * @throws IOException             if an IO error arises while writing the entity
     * @throws WebApplicationException if the entity cannot be written
     */
    protected void captureRequestBody(WriterInterceptorContext context) throws IOException, WebApplicationException {
        if (!logRequestBody || !isLoggingEnabled()) {
            context.proceed();
            return;
        }

        LoggedBodyCapture capture = startCapture(() -> {
            LoggedBodyCapture started = createBodyCapture(requestBodyLimit);
            context.setOutputStream(new TeeOutputStream(context.getOutputStream(), started.sink()));
            return started;
        });
        try {
            context.proceed();
        } finally {
            // Logs whatever was captured even if writing the entity failed (e.g. connection reset before
            // the body was fully sent), mirroring aroundReadFrom's handling of the response body below
            if (capture != null) {
                safely(() -> {
                    String body = capture.content(getBodyFilters(), context.getMediaType());
                    if (isNotBlank(body)) {
                        log.info("Request body {} {}{}{}",
                                context.getProperty(REQUEST_METHOD_PROPERTY),
                                context.getProperty(REQUEST_URI_PROPERTY),
                                LF,
                                body);
                    }
                });
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Falls back to a zero duration when {@link #REQUEST_TIME_PROPERTY} was never set, which happens
     * when a client request filter running before this one (lower {@link Priority} value) aborts the
     * request with {@link ClientRequestContext#abortWith(jakarta.ws.rs.core.Response)}: response filters
     * still run for an aborted request, but {@link #filter(ClientRequestContext)} above, where this
     * provider would otherwise have recorded the start time, never does.
     * <p>
     * Guarded like everything else this provider does (see {@link LoggedSupport#safely}): a response
     * already received is not worth turning into a failed call because the line reporting it could not
     * be written.
     */
    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
        safely(() -> {
            long requestStartTime = Optional.ofNullable(requestContext.getProperty(REQUEST_TIME_PROPERTY))
                    .map(Number.class::cast)
                    .map(Number::longValue)
                    .orElseGet(System::nanoTime);
            long duration = (nanoTime() - requestStartTime) / 1_000_000;

            log.atLevel(getResponseLevel(responseContext.getStatus()))
                    .log("Called {} {} with status {} in {}ms",
                            requestContext.getMethod(),
                            requestContext.getUri(),
                            responseContext.getStatus(),
                            duration);
        });
    }

    /**
     * Gets the level at which a call answered with the given status is logged, see
     * {@link LoggedSupport#levelOf(int)}, which covers both sides of the call.
     *
     * @param status The status of the response received
     * @return The level to log the call at
     */
    protected Level getResponseLevel(int status) {
        return LoggedSupport.levelOf(status);
    }

    /**
     * Captures and logs the response body while the entity is being read.
     * <p>
     * Note that this is only invoked when the calling code actually reads the response entity (e.g.
     * {@code response.readEntity(MyType.class)}), which can happen after - or not at all after - the
     * "Called ..." line has already been logged by {@link #filter(ClientRequestContext, ClientResponseContext)}.
     * If the entity is never read, the response body is never logged, even if activated.
     *
     * @param context The context of the entity being read
     * @return The entity read
     * @throws IOException             if an IO error arises while reading the entity
     * @throws WebApplicationException if the entity cannot be read
     */
    protected Object captureResponseBody(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        if (!logResponseBody || !isLoggingEnabled()) {
            return context.proceed();
        }

        LoggedBodyCapture capture = startCapture(() -> {
            LoggedBodyCapture started = createBodyCapture(responseBodyLimit);
            context.setInputStream(new TeeInputStream(context.getInputStream(), started.sink()));
            return started;
        });
        try {
            return context.proceed();
        } finally {
            if (capture != null) {
                safely(() -> {
                    String body = capture.content(getBodyFilters(), context.getMediaType());
                    if (isNotBlank(body)) {
                        log.info("Response body {} {}{}{}",
                                context.getProperty(REQUEST_METHOD_PROPERTY),
                                context.getProperty(REQUEST_URI_PROPERTY),
                                LF,
                                body);
                    }
                });
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Registering this provider also registers {@link BodyInterceptor}, so a single
     * {@code client.register(...)} keeps covering bodies. The two cannot be the same provider because
     * they need different priorities: the filters above must run early on the request and late on the
     * response, which places them outside any entity coder, while capturing a body requires running
     * inside it (see {@link BodyInterceptor}).
     */
    @Override
    public boolean configure(FeatureContext context) {
        context.register(new BodyInterceptor(this));
        return true;
    }

    /**
     * Captures the bodies logged by the {@link LoggedClientFilter} that registered it, from a position in
     * the interceptor chain where they are readable.
     * <p>
     * Interceptors are invoked in ascending priority order and each one wraps the stream for those that
     * run after it, so the first to run sits closest to the network and an entity coder
     * ({@link Priorities#ENTITY_CODER}) registered after it compresses on the way out and decompresses on
     * the way in, between that interceptor and the entity itself. Capturing from the enclosing filter's
     * own priority therefore captured the compressed bytes: a {@code Content-Encoding: gzip} request or
     * response was logged as gzip noise rather than as the payload the application actually sent or read.
     * <p>
     * Running after the entity coder instead, what is captured is the entity's own representation,
     * whatever transfer encoding was applied around it. Every decision about it stays on the enclosing
     * filter, which this interceptor calls back into, so a subclass overriding
     * {@link #createBodyCapture(int)} or either capture method stays in control.
     */
    @ConstrainedTo(CLIENT)
    @Priority(Priorities.ENTITY_CODER + 100)
    public static class BodyInterceptor implements ReaderInterceptor, WriterInterceptor {

        protected final LoggedClientFilter filter;

        /**
         * Creates the interceptor capturing bodies for the given filter.
         *
         * @param filter The filter to hand the captured bodies back to
         */
        public BodyInterceptor(LoggedClientFilter filter) {
            this.filter = filter;
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
            filter.captureRequestBody(context);
        }

        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
            return filter.captureResponseBody(context);
        }

    }

    /**
     * Runs the given logging action, swallowing anything it throws, so that logging a call can never be
     * the reason it fails. See {@link LoggedSupport#safely(Logger, String, Runnable)}.
     *
     * @param action The logging action to run
     */
    protected void safely(Runnable action) {
        LoggedSupport.safely(log, "Unable to log the client call, the call itself is left unaffected", action);
    }

    /**
     * Runs the given body capture setup, returning {@code null} rather than letting a failure out, so a
     * body that cannot be captured is left out of the logs instead of failing the call. See
     * {@link LoggedSupport#startCapture(Logger, String, Supplier)} for why this one piece of logging work
     * needs a guard of its own.
     *
     * @param setup The setup creating the capture and wrapping the entity stream with it
     * @return The capture put in place, or {@code null} if it could not be
     */
    protected LoggedBodyCapture startCapture(Supplier<LoggedBodyCapture> setup) {
        return LoggedSupport.startCapture(log, "Unable to capture the body, it is left out of the logs, the call itself is left unaffected", setup);
    }

    /**
     * Creates the {@link LoggedBodyCapture} used to capture a request or response body.
     * <p>
     * Same extension point as {@link LoggedFilter#createBodyCapture(int)}: override to plug in a
     * different body capture strategy.
     *
     * @param limit The maximum size of the body to capture in bytes, or {@code -1} for no limit
     * @return The body capture to use
     */
    protected LoggedBodyCapture createBodyCapture(int limit) {
        return new BoundedLoggedBodyCapture(limit);
    }

    /**
     * Gets the filter instances configured for this client filter.
     *
     * @return The set of filter instances to be applied, iterating in the order the classes were declared
     * through {@link Builder#bodyFilters(Class[])}, so filters that depend on one another's output run
     * predictably
     */
    protected Set<LoggedBodyFilter> getBodyFilters() {
        return bodyFilters;
    }

    /**
     * Indicates whether anything this provider writes would actually reach an appender.
     * <p>
     * Used to skip body capture entirely when it would be thrown away, see
     * {@link LoggedFilter#isLoggingEnabled()}.
     *
     * @return {@code true} if the log lines written by this provider are enabled, {@code false} otherwise
     */
    protected boolean isLoggingEnabled() {
        return log.isInfoEnabled();
    }

}
