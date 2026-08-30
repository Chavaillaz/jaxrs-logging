package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static jakarta.ws.rs.RuntimeType.CLIENT;
import static java.lang.System.nanoTime;
import static java.util.Objects.requireNonNullElseGet;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
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
 * available if/when the calling code actually reads the response entity (see {@link #aroundReadFrom},
 * mirroring the equivalent, well-known limitation on {@link LoggedFilter#aroundReadFrom} for the request
 * body), which can happen after - or not at all after - the "Called ..." line above already logged, so
 * there is no single point at which both the status line and the body are guaranteed to be available
 * together to merge into one line the way the server-side filter does.
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
public class LoggedClientFilter implements ClientRequestFilter, ClientResponseFilter, ReaderInterceptor, WriterInterceptor {

    protected static final Logger log = LoggerFactory.getLogger(LoggedClientFilter.class);

    /**
     * Name of the property stored in request context to compute the duration time.
     */
    protected static final String REQUEST_TIME_PROPERTY = LoggedClientFilter.class.getName() + ".requestTime";

    /**
     * Name of the property stored in request context to retrieve the request method when logging the
     * request body from {@link #aroundWriteTo(WriterInterceptorContext)}, which has no direct access to
     * the {@link ClientRequestContext} the method was read from.
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
    protected final Set<Class<? extends LoggedBodyFilter>> bodyFilterClasses;

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
        this.bodyFilterClasses = builder.bodyFilterClasses;
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
        private final Set<Class<? extends LoggedBodyFilter>> bodyFilterClasses = new HashSet<>();

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
         * Adds filters to be applied (both directions) before logging a body.
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
         * Builds the {@link LoggedClientFilter} configured by this builder.
         *
         * @return The client filter created
         */
        public LoggedClientFilter build() {
            return new LoggedClientFilter(this);
        }

    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        requestContext.setProperty(REQUEST_TIME_PROPERTY, nanoTime());
        requestContext.setProperty(REQUEST_METHOD_PROPERTY, requestContext.getMethod());
        requestContext.setProperty(REQUEST_URI_PROPERTY, requestContext.getUri().toString());

        if (!requestContext.getHeaders().containsKey(REQUEST_ID_HEADER)) {
            String correlationId = requireNonNullElseGet(MDC.get(correlationIdMdcKey), () -> randomUUID().toString());
            requestContext.getHeaders().putSingle(REQUEST_ID_HEADER, correlationId);
        }

        log.info("Calling {} {}", requestContext.getMethod(), requestContext.getUri());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Note that this is only invoked when the request actually has an entity to write, which is the
     * common case for a client call built with an entity (e.g. {@code target.request().post(entity)}).
     */
    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        if (!logRequestBody) {
            context.proceed();
            return;
        }

        LoggedBodyCapture capture = createBodyCapture(requestBodyLimit);
        TeeOutputStream teeOutputStream = new TeeOutputStream(context.getOutputStream(), capture.sink());
        context.setOutputStream(teeOutputStream);
        context.proceed();

        String body = capture.content(getBodyFilters());
        if (isNotBlank(body)) {
            log.info("Request body {} {}{}{}",
                    context.getProperty(REQUEST_METHOD_PROPERTY),
                    context.getProperty(REQUEST_URI_PROPERTY),
                    LF,
                    body);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Falls back to a zero duration when {@value #REQUEST_TIME_PROPERTY} was never set, which happens
     * when a client request filter running before this one (lower {@link Priority} value) aborts the
     * request with {@link ClientRequestContext#abortWith(jakarta.ws.rs.core.Response)}: response filters
     * still run for an aborted request, but {@link #filter(ClientRequestContext)} above, where this
     * provider would otherwise have recorded the start time, never does.
     */
    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
        long requestStartTime = Optional.ofNullable(requestContext.getProperty(REQUEST_TIME_PROPERTY))
                .map(Number.class::cast)
                .map(Number::longValue)
                .orElseGet(System::nanoTime);
        long duration = (nanoTime() - requestStartTime) / 1_000_000;

        log.info("Called {} {} with status {} in {}ms",
                requestContext.getMethod(),
                requestContext.getUri(),
                responseContext.getStatus(),
                duration);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Note that this is only invoked when the calling code actually reads the response entity (e.g.
     * {@code response.readEntity(MyType.class)}), which can happen after - or not at all after - the
     * "Called ..." line has already been logged by {@link #filter(ClientRequestContext, ClientResponseContext)}.
     * If the entity is never read, the response body is never logged, even if activated.
     */
    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        if (!logResponseBody) {
            return context.proceed();
        }

        LoggedBodyCapture capture = createBodyCapture(responseBodyLimit);
        TeeInputStream teeInputStream = new TeeInputStream(context.getInputStream(), capture.sink());
        context.setInputStream(teeInputStream);
        try {
            return context.proceed();
        } finally {
            String body = capture.content(getBodyFilters());
            if (isNotBlank(body)) {
                log.info("Response body {} {}{}{}",
                        context.getProperty(REQUEST_METHOD_PROPERTY),
                        context.getProperty(REQUEST_URI_PROPERTY),
                        LF,
                        body);
            }
        }
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
     * @return The set of filter instances to be applied
     */
    protected Set<LoggedBodyFilter> getBodyFilters() {
        return bodyFilterClasses.stream()
                .map(bodyFilterFactory::getInstance)
                .collect(toSet());
    }

}
