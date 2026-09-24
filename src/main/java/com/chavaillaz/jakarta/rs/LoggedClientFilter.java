package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static jakarta.ws.rs.RuntimeType.CLIENT;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
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
 * Logs the following lines, with the credentials the URI may carry masked (see {@link #getLoggedUri(URI)}):
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
     * Name of the property stored in request context to retrieve the request URI as it is logged (see
     * {@link #getLoggedUri(URI)}), for the same reason as {@link #REQUEST_METHOD_PROPERTY}.
     */
    protected static final String REQUEST_URI_PROPERTY = LoggedClientFilter.class.getName() + ".requestUri";

    /**
     * Name of the property stored in request context to record that the response body of the call has been
     * logged, see {@link #captureResponseBody(ReaderInterceptorContext)}.
     */
    protected static final String RESPONSE_BODY_LOGGED_PROPERTY = LoggedClientFilter.class.getName() + ".responseBodyLogged";

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
        private int requestBodyLimit = LoggedBodyCapture.NO_LIMIT;
        private int responseBodyLimit = LoggedBodyCapture.NO_LIMIT;
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
         * @throws NullPointerException if the key is {@code null}, as MDC cannot read an entry without one
         */
        public Builder correlationIdKey(String mdcKey) {
            this.correlationIdMdcKey = requireNonNull(mdcKey, "The MDC key of the correlation identifier is required");
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
         * @throws IllegalArgumentException if the limit is lower than {@code -1}
         */
        public Builder bodyLimit(int limit) {
            return requestBodyLimit(limit).responseBodyLimit(limit);
        }

        /**
         * Sets the size limit in bytes applied to the request body when logged.
         *
         * @param limit The maximum size of the body to be logged in bytes, or {@code -1} for no limit
         * @return This builder
         * @throws IllegalArgumentException if the limit is lower than {@code -1}
         */
        public Builder requestBodyLimit(int limit) {
            this.requestBodyLimit = checkLimit(limit);
            return this;
        }

        /**
         * Sets the size limit in bytes applied to the response body when logged.
         *
         * @param limit The maximum size of the body to be logged in bytes, or {@code -1} for no limit
         * @return This builder
         * @throws IllegalArgumentException if the limit is lower than {@code -1}
         */
        public Builder responseBodyLimit(int limit) {
            this.responseBodyLimit = checkLimit(limit);
            return this;
        }

        /**
         * Adds filters to be applied (both directions) before logging a body, in the given order (and in
         * declaration order across multiple calls), so filters that depend on one another's output run
         * predictably.
         *
         * @param filters The filter classes to add
         * @return This builder
         * @throws NullPointerException if any of the filters is {@code null}
         */
        @SafeVarargs
        public final Builder bodyFilters(Class<? extends LoggedBodyFilter>... filters) {
            bodyFilterClasses.addAll(nonNull(filters, "A body filter class is required"));
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
         * @throws NullPointerException if any of the filters is {@code null}
         */
        public Builder bodyFilters(LoggedBodyFilter... filters) {
            bodyFilterInstances.addAll(nonNull(filters, "A body filter is required"));
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

        /**
         * Checks the given body size limit, which is rejected here rather than by the capture it is given to:
         * there, it would fail every call, and leave every body out of the logs.
         *
         * @param limit The maximum size of the body to be logged in bytes, or {@code -1} for no limit
         * @return The limit, valid
         * @throws IllegalArgumentException if the limit is lower than {@code -1}
         */
        private static int checkLimit(int limit) {
            return CaptureBuffer.checkLimit(limit);
        }

        /**
         * Lists the given elements, checking none of them is {@code null} before anything is added from them.
         *
         * @param elements The elements to list
         * @param message  The message of the exception thrown for a {@code null} element
         * @param <T>      The type of the elements
         * @return The elements, as a list
         * @throws NullPointerException if any of the elements is {@code null}
         */
        private static <T> List<T> nonNull(T[] elements, String message) {
            List<T> list = Arrays.asList(elements);
            list.forEach(element -> requireNonNull(element, message));
            return list;
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
            String uri = getLoggedUri(requestContext.getUri());
            requestContext.setProperty(REQUEST_TIME_PROPERTY, nanoTime());
            requestContext.setProperty(REQUEST_METHOD_PROPERTY, requestContext.getMethod());
            requestContext.setProperty(REQUEST_URI_PROPERTY, uri);

            // HTTP header names are case-insensitive, but the client-side header map is not guaranteed to be
            // (it is a plain MultivaluedMap in the JAX-RS Client API), so a caller having already set the
            // header under a different casing would otherwise get it sent twice with two different values
            if (requestContext.getHeaders().keySet().stream().noneMatch(REQUEST_ID_HEADER::equalsIgnoreCase)) {
                String correlationId = requireNonNullElseGet(MDC.get(correlationIdMdcKey), () -> randomUUID().toString());
                requestContext.getHeaders().putSingle(REQUEST_ID_HEADER, correlationId);
            }

            log.info("Calling {} {}", requestContext.getMethod(), uri);
        });
    }

    /**
     * Renders the given URI the way it is logged, with the credentials it may carry masked.
     * <p>
     * Unlike {@link LoggedFilter}, which logs the path of a request and its query parameters apart and can
     * mask the latter one by one, this provider logs the URI of a call whole - and a URI is a common place
     * for a credential to travel in: the password of its user information ({@code https://user:secret@host}),
     * the {@code access_token} of an OAuth call, the signature of a presigned URL. Logging it as-is wrote
     * all of them to the logs of every call made, with nothing to configure to prevent it.
     * <p>
     * The user information is therefore replaced as a whole, since it is either a credential or the name
     * going with one, and the value of every query parameter {@link #isSensitiveQueryParameter(String)}
     * reports is masked while its name stays visible, the way {@link LoggedFilter} masks the query
     * parameters of the requests it receives. Everything else is left exactly as it was given.
     *
     * @param uri The URI of the call
     * @return The URI as it must be logged
     */
    protected String getLoggedUri(URI uri) {
        if (uri.isOpaque() || (uri.getRawUserInfo() == null && uri.getRawQuery() == null)) {
            // Nothing to mask, which is the case of almost every call: returned without being rebuilt
            return uri.toString();
        }

        StringBuilder rendered = new StringBuilder();
        if (uri.getScheme() != null) {
            rendered.append(uri.getScheme()).append(':');
        }
        if (uri.getRawAuthority() != null) {
            String authority = uri.getRawAuthority();
            String userInfo = uri.getRawUserInfo();
            rendered.append("//").append(userInfo == null
                    ? authority
                    : MaskingBodyFilter.DEFAULT_MASK + authority.substring(userInfo.length()));
        }
        rendered.append(uri.getRawPath());
        if (uri.getRawQuery() != null) {
            rendered.append('?').append(maskQuery(uri.getRawQuery()));
        }
        if (uri.getRawFragment() != null) {
            rendered.append('#').append(uri.getRawFragment());
        }
        return rendered.toString();
    }

    /**
     * Masks the value of every parameter of the given raw query that {@link #isSensitiveQueryParameter(String)}
     * reports, leaving the other parameters, and the encoding of all of them, as they were.
     *
     * @param rawQuery The query of a URI, still encoded
     * @return The query with the values of its sensitive parameters masked
     */
    private String maskQuery(String rawQuery) {
        return Arrays.stream(rawQuery.split("&", -1))
                .map(parameter -> {
                    int separator = parameter.indexOf('=');
                    // Decoded before being compared, so a name the caller escaped is recognized all the same;
                    // the raw components of a java.net.URI are always validly encoded, so this cannot fail
                    return separator >= 0 && isSensitiveQueryParameter(URLDecoder.decode(parameter.substring(0, separator), UTF_8))
                            ? parameter.substring(0, separator + 1) + MaskingBodyFilter.DEFAULT_MASK
                            : parameter;
                })
                .collect(joining("&"));
    }

    /**
     * Indicates whether the value of the query parameter of the given name must be kept out of the logs,
     * see {@link #getLoggedUri(URI)}.
     * <p>
     * The defaults come from {@link CredentialNames}, which only knows what callers conventionally name
     * their secrets. Override to extend (or restrict) them for the services an application calls, for
     * example to also mask the key a partner API expects in its query string:
     * <pre>{@code
     * @Override
     * protected boolean isSensitiveQueryParameter(String name) {
     *     return super.isSensitiveQueryParameter(name) || "partner-key".equalsIgnoreCase(name);
     * }
     * }</pre>
     *
     * @param name The decoded name of the query parameter
     * @return {@code true} if the value must be kept out of the logs, {@code false} otherwise
     */
    protected boolean isSensitiveQueryParameter(String name) {
        return CredentialNames.isQueryParameter(name);
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

        LoggedBodyCapture capture = startCapture(
                () -> createBodyCapture(requestBodyLimit),
                started -> context.setOutputStream(new TeeOutputStream(context.getOutputStream(), started.sink())));
        try {
            context.proceed();
        } finally {
            // Logs whatever was captured even if writing the entity failed (e.g. connection reset before
            // the body was fully sent), mirroring aroundReadFrom's handling of the response body below
            if (capture != null) {
                endCapture(capture, () -> {
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
                            getLoggedUri(requestContext.getUri()),
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
     * <p>
     * A buffered entity can be read any number of times - {@code bufferEntity()} then {@code readEntity()}
     * once per type the calling code tries - and every read goes through the interceptors again, so a body
     * already logged for the call (see {@link #RESPONSE_BODY_LOGGED_PROPERTY}) is neither captured nor
     * logged again.
     *
     * @param context The context of the entity being read
     * @return The entity read
     * @throws IOException             if an IO error arises while reading the entity
     * @throws WebApplicationException if the entity cannot be read
     */
    protected Object captureResponseBody(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        if (!logResponseBody || !isLoggingEnabled() || Boolean.TRUE.equals(context.getProperty(RESPONSE_BODY_LOGGED_PROPERTY))) {
            return context.proceed();
        }

        LoggedBodyCapture capture = startCapture(
                () -> createBodyCapture(responseBodyLimit),
                started -> context.setInputStream(new TeeInputStream(context.getInputStream(), started.sink())));
        try {
            return context.proceed();
        } finally {
            if (capture != null) {
                endCapture(capture, () -> {
                    String body = capture.content(getBodyFilters(), context.getMediaType());
                    if (isNotBlank(body)) {
                        context.setProperty(RESPONSE_BODY_LOGGED_PROPERTY, true);
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
     * {@link LoggedSupport#startCapture(Logger, String, Supplier, Consumer)} for why this one piece of
     * logging work needs a guard of its own.
     *
     * @param factory The creation of the capture
     * @param wiring  The wrapping of the entity stream with the created capture
     * @return The capture put in place, or {@code null} if it could not be
     */
    protected LoggedBodyCapture startCapture(Supplier<LoggedBodyCapture> factory, Consumer<LoggedBodyCapture> wiring) {
        return LoggedSupport.startCapture(log, "Unable to capture the body, it is left out of the logs, the call itself is left unaffected", factory, wiring);
    }

    /**
     * Runs the given action reading what a capture collected and releases that capture afterwards,
     * swallowing anything either of them throws. See
     * {@link LoggedSupport#endCapture(Logger, String, LoggedBodyCapture, Runnable)}.
     *
     * @param capture The capture to read from and release
     * @param action  The action reading what the capture collected
     */
    protected void endCapture(LoggedBodyCapture capture, Runnable action) {
        LoggedSupport.endCapture(log, "Unable to log the captured body or to release the capture, the call itself is left unaffected", capture, action);
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
