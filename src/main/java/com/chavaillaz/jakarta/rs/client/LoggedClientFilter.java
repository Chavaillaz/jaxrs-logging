package com.chavaillaz.jakarta.rs.client;

import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static com.chavaillaz.jakarta.rs.LoggedSupport.levelOf;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.NO_LIMIT;
import static com.chavaillaz.jakarta.rs.filter.MaskingBodyFilter.DEFAULT_MASK;
import static jakarta.ws.rs.RuntimeType.CLIENT;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.chavaillaz.jakarta.rs.LoggedBody;
import com.chavaillaz.jakarta.rs.LoggedField;
import com.chavaillaz.jakarta.rs.LoggedFilter;
import com.chavaillaz.jakarta.rs.LoggedFilterConfiguration;
import com.chavaillaz.jakarta.rs.LoggedSupport;
import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.BodyCapturer;
import com.chavaillaz.jakarta.rs.internal.CredentialNames;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyConfiguration;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyFilterFactory;
import com.chavaillaz.jakarta.rs.internal.LoggingGuard;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

/**
 * Client-side counterpart of {@link LoggedFilter}, logging the calls made through a JAX-RS {@code Client}
 * and propagating the identifier of the current request (see {@link LoggedField#REQUEST_ID}) to the service
 * called, as {@value LoggedFilter#REQUEST_ID_HEADER}, so both sides of a call are logged under one identifier.
 * <p>
 * Having no resource method to read annotations from, it is configured through {@link #builder()}, and
 * applies to every call made through the {@code Client} or {@code WebTarget} it is registered on. It logs,
 * with the credentials the URI of a call may carry masked (see {@link #getLoggedUri(URI)}):
 * <ul>
 *     <li>{@code Calling [method] [uri]}, once the request is about to be sent</li>
 *     <li>{@code Called [method] [uri] with status [status] in [duration]ms}, once the response is received</li>
 * </ul>
 * A body is logged on a line of its own: the response body is only read if and when the calling code reads
 * the entity (see {@link #captureResponseBody(ReaderInterceptorContext)}), possibly long after the
 * {@code "Called ..."} line, and possibly never. For the same reason, only {@link LoggedBody.LogType#LOG} is
 * supported, as nothing marks the end of a call that an MDC entry holding the body could be scoped to.
 */
@Provider
@ConstrainedTo(CLIENT)
@Priority(Priorities.HEADER_DECORATOR)
public class LoggedClientFilter implements ClientRequestFilter, ClientResponseFilter, Feature {

    /**
     * Logger the calls, their bodies and the failures to log them are written to.
     */
    protected static final Logger log = LoggerFactory.getLogger(LoggedClientFilter.class);

    /**
     * Name of the request property holding the moment the call started, to compute its duration.
     */
    protected static final String REQUEST_TIME_PROPERTY = LoggedClientFilter.class.getName() + ".requestTime";

    /**
     * Name of the request property holding the method of the call, for the lines logging its bodies, which an
     * interceptor context does not give access to.
     */
    protected static final String REQUEST_METHOD_PROPERTY = LoggedClientFilter.class.getName() + ".requestMethod";

    /**
     * Name of the request property holding the URI of the call as it is logged (see {@link #getLoggedUri(URI)}),
     * for the same reason as {@link #REQUEST_METHOD_PROPERTY}.
     */
    protected static final String REQUEST_URI_PROPERTY = LoggedClientFilter.class.getName() + ".requestUri";

    /**
     * Name of the request property recording that the response body of the call has been logged, see
     * {@link #captureResponseBody(ReaderInterceptorContext)}.
     */
    protected static final String RESPONSE_BODY_LOGGED_PROPERTY = LoggedClientFilter.class.getName() + ".responseBodyLogged";

    /**
     * Instantiates and caches the body filters given as classes.
     */
    private final LoggedBodyFilterFactory bodyFilterFactory = new LoggedBodyFilterFactory();

    /**
     * Key of the MDC entry holding the identifier propagated to the services called.
     */
    protected final String correlationIdMdcKey;

    /**
     * Body logging configuration of the requests sent, fixed when this provider is built.
     */
    private final LoggedBodyConfiguration requestBody;

    /**
     * Body logging configuration of the responses received, fixed when this provider is built.
     */
    private final LoggedBodyConfiguration responseBody;

    /**
     * Captures the bodies of the requests written and the responses read, through
     * {@link #createBodyCapture(int)}.
     */
    private final BodyCapturer bodyCapturer = new BodyCapturer(log, this::createBodyCapture);

    /**
     * Creates a new client filter with the default configuration (no body logging, correlation identifier
     * read from the {@code request-id} MDC key). Use {@link #builder()} to customize it.
     */
    public LoggedClientFilter() {
        this(builder());
    }

    /**
     * Creates a client filter configured by the given builder, for a subclass to call from its constructors.
     *
     * @param builder The builder holding the configuration
     */
    protected LoggedClientFilter(Builder builder) {
        this.correlationIdMdcKey = builder.correlationIdMdcKey;
        // Classes first, then instances, keeping the declaration order within each: a filter given as a
        // class cannot depend on one given as an instance without the caller having built both anyway
        Set<LoggedBodyFilter> filters = new LinkedHashSet<>(bodyFilterFactory.getInstances(builder.bodyFilterClasses));
        filters.addAll(builder.bodyFilterInstances);
        Set<LoggedBodyFilter> bodyFilters = unmodifiableSet(filters);
        this.requestBody = bodyConfiguration(builder.logRequestBody, builder.requestBodyLimit, bodyFilters);
        this.responseBody = bodyConfiguration(builder.logResponseBody, builder.responseBodyLimit, bodyFilters);
    }

    /**
     * Creates the body logging configuration of one side of the calls made.
     *
     * @param logged  Whether the body is logged
     * @param limit   The maximum size of the body to log in bytes, or {@code -1} for no limit
     * @param filters The filters to apply to the body before logging it, in the order they apply
     * @return The body logging configuration
     */
    private static LoggedBodyConfiguration bodyConfiguration(boolean logged, int limit, Set<LoggedBodyFilter> filters) {
        // Only a separate log line is supported on this side, see the class documentation
        return logged
                ? new LoggedBodyConfiguration(Set.of(LOG), limit, filters)
                : LoggedBodyConfiguration.NONE;
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
     * Builder of {@link LoggedClientFilter}, starting from the default of every setting.
     */
    public static final class Builder {

        private String correlationIdMdcKey = LoggedField.REQUEST_ID.getDefaultField();
        private boolean logRequestBody = false;
        private boolean logResponseBody = false;
        private int requestBodyLimit = NO_LIMIT;
        private int responseBodyLimit = NO_LIMIT;
        private final Set<Class<? extends LoggedBodyFilter>> bodyFilterClasses = new LinkedHashSet<>();
        private final Set<LoggedBodyFilter> bodyFilterInstances = new LinkedHashSet<>();

        private Builder() {
            // Created through LoggedClientFilter.builder()
        }

        /**
         * Sets the MDC key read to obtain the identifier propagated as {@value LoggedFilter#REQUEST_ID_HEADER}
         * on outgoing requests, falling back to a random one when absent from MDC (e.g. no {@link LoggedFilter}
         * is active on the calling thread). Defaults to {@code request-id}; change it to match a renamed
         * {@link LoggedField#REQUEST_ID} MDC key (see
         * {@link LoggedFilterConfiguration.Builder#fieldName(LoggedField, String)}).
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
            return LoggedBodyCapture.checkLimit(limit);
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
            List<T> list = asList(elements);
            list.forEach(element -> requireNonNull(element, message));
            return list;
        }

    }

    /**
     * {@inheritDoc}
     * <p>
     * Propagates the correlation identifier and logs the {@code "Calling ..."} line. Nothing done here can
     * fail the call, which is made all the same when it cannot be described.
     */
    @Override
    public void filter(ClientRequestContext requestContext) {
        // Guarded apart from the description of the call, so a call that cannot be described still carries
        // the identifier correlating it with the service it reaches
        safely(() -> propagateCorrelationId(requestContext));
        safely(() -> {
            requestContext.setProperty(REQUEST_TIME_PROPERTY, nanoTime());
            String uri = getLoggedUri(requestContext.getUri());
            requestContext.setProperty(REQUEST_METHOD_PROPERTY, requestContext.getMethod());
            requestContext.setProperty(REQUEST_URI_PROPERTY, uri);
            log.info("Calling {} {}", requestContext.getMethod(), uri);
        });
    }

    /**
     * Sets the correlation identifier on the given request, unless it already carries one.
     *
     * @param requestContext The context of the request about to be sent
     */
    private void propagateCorrelationId(ClientRequestContext requestContext) {
        // HTTP header names are case-insensitive, but the client-side header map is not guaranteed to be (it
        // is a plain MultivaluedMap in the JAX-RS Client API), so a caller having already set the header under
        // a different casing would otherwise get it sent twice with two different values
        if (requestContext.getHeaders().keySet().stream().noneMatch(REQUEST_ID_HEADER::equalsIgnoreCase)) {
            String correlationId = requireNonNullElseGet(MDC.get(correlationIdMdcKey), () -> randomUUID().toString());
            requestContext.getHeaders().putSingle(REQUEST_ID_HEADER, correlationId);
        }
    }

    /**
     * Renders the given URI the way it is logged, with the credentials it may carry masked: the password of
     * its user information ({@code https://user:secret@host}), the {@code access_token} of an OAuth call, the
     * signature of a presigned URL.
     * <p>
     * The user information is replaced as a whole, being either a credential or the name going with one,
     * and the value of every query parameter {@link #isSensitiveQueryParameter(String)} reports is masked
     * while its name stays visible, as {@link LoggedFilter} does for the requests it receives. Everything else
     * is left exactly as it was given.
     *
     * @param uri The URI of the call
     * @return The URI as it must be logged
     */
    protected String getLoggedUri(URI uri) {
        String authority = uri.getRawAuthority();
        // The user information ends at the last '@' of the authority, a character it cannot contain itself.
        // It is found there rather than through getRawUserInfo(), which is null for an authority that
        // java.net.URI cannot parse as a host and a port - a host name with an underscore, as containers are
        // commonly named - although the credentials it holds are just as real
        int userInfoEnd = authority == null ? -1 : authority.lastIndexOf('@');
        if (uri.isOpaque() || (userInfoEnd < 0 && uri.getRawQuery() == null)) {
            // Nothing to mask, which is the case of almost every call: returned without being rebuilt
            return uri.toString();
        }

        StringBuilder rendered = new StringBuilder();
        if (uri.getScheme() != null) {
            rendered.append(uri.getScheme()).append(':');
        }
        if (authority != null) {
            rendered.append("//").append(userInfoEnd < 0
                    ? authority
                    : DEFAULT_MASK + authority.substring(userInfoEnd));
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
        return stream(rawQuery.split("&", -1))
                .map(parameter -> {
                    int separator = parameter.indexOf('=');
                    // Decoded before being compared, so a name the caller escaped is recognized all the same;
                    // the raw components of a java.net.URI are always validly encoded, so this cannot fail
                    return separator >= 0 && isSensitiveQueryParameter(URLDecoder.decode(parameter.substring(0, separator), UTF_8))
                            ? parameter.substring(0, separator + 1) + DEFAULT_MASK
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
     * Captures and logs the request body while the entity is being written, which only happens for a call
     * made with an entity (e.g. {@code target.request().post(entity)}). What was written is logged even when
     * writing the rest failed.
     *
     * @param context The context of the entity being written
     * @throws IOException             if an IO error arises while writing the entity
     * @throws WebApplicationException if the entity cannot be written
     */
    protected void captureRequestBody(WriterInterceptorContext context) throws IOException, WebApplicationException {
        bodyCapturer.write(context, isLoggingEnabled() ? requestBody : LoggedBodyConfiguration.NONE, body -> {
            if (isNotBlank(body)) {
                log.info("Request body {} {}{}{}",
                        context.getProperty(REQUEST_METHOD_PROPERTY),
                        context.getProperty(REQUEST_URI_PROPERTY),
                        LF,
                        body);
            }
        });
    }

    /**
     * {@inheritDoc}
     * <p>
     * Logs the {@code "Called ..."} line, at the level {@link #getResponseLevel(int)} gives the status. Nothing
     * done here can fail the call, whose response has been received.
     * <p>
     * A call aborted by a request filter running earlier ({@link ClientRequestContext#abortWith}) never
     * reaches {@link #filter(ClientRequestContext)}, where it starts, and is logged with a zero duration.
     */
    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
        safely(() -> {
            long now = nanoTime();
            long start = requestContext.getProperty(REQUEST_TIME_PROPERTY) instanceof Long started ? started : now;
            long duration = NANOSECONDS.toMillis(now - start);
            int status = responseContext.getStatus();

            log.atLevel(requireNonNullElseGet(getResponseLevel(status), () -> levelOf(status)))
                    .log("Called {} {} with status {} in {}ms",
                            requestContext.getMethod(),
                            getLoggedUri(requestContext.getUri()),
                            status,
                            duration);
        });
    }

    /**
     * Gets the level at which a call answered with the given status is logged, see
     * {@link LoggedSupport#levelOf(int)}, which covers both sides of the call.
     *
     * @param status The status of the response received
     * @return The level to log the call at, {@code null} leaving the status at its default level
     */
    protected @Nullable Level getResponseLevel(int status) {
        return levelOf(status);
    }

    /**
     * Captures and logs the response body while the entity is being read, which only happens when the calling
     * code reads it (e.g. {@code response.readEntity(MyType.class)}): a response whose entity is never read has
     * its body never logged.
     * <p>
     * A buffered entity can be read any number of times, each read going through the interceptors again: a
     * body already logged for the call (see {@link #RESPONSE_BODY_LOGGED_PROPERTY}) is not logged again.
     *
     * @param context The context of the entity being read
     * @return The entity read
     * @throws IOException             if an IO error arises while reading the entity
     * @throws WebApplicationException if the entity cannot be read
     */
    protected @Nullable Object captureResponseBody(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        boolean alreadyLogged = Boolean.TRUE.equals(context.getProperty(RESPONSE_BODY_LOGGED_PROPERTY));
        LoggedBodyConfiguration configuration = isLoggingEnabled() && !alreadyLogged ? responseBody : LoggedBodyConfiguration.NONE;
        return bodyCapturer.read(context, configuration, body -> {
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

    /**
     * {@inheritDoc}
     * <p>
     * Registers the {@link BodyInterceptor} capturing the bodies of the calls along with this provider, so
     * registering this provider is enough. The two need priorities of their own: the filters run first on
     * the request and last on the response, outside any entity coder, while a body is captured inside it.
     */
    @Override
    public boolean configure(FeatureContext context) {
        context.register(new BodyInterceptor(this));
        return true;
    }

    /**
     * Captures the bodies logged by the {@link LoggedClientFilter} that registered it, from after any entity
     * coder ({@link Priorities#ENTITY_CODER}), so what it captures is the entity rather than its transfer
     * encoding - a {@code Content-Encoding: gzip} body is logged as the payload, not as compressed bytes.
     * <p>
     * Every decision about a body stays with the filter, which this interceptor calls back, so a subclass
     * overriding {@link #createBodyCapture(int)} or either capture method stays in control.
     */
    @ConstrainedTo(CLIENT)
    @Priority(Priorities.ENTITY_CODER + 100)
    public static class BodyInterceptor implements ReaderInterceptor, WriterInterceptor {

        /**
         * Filter the bodies captured are handed back to.
         */
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
        public @Nullable Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
            return filter.captureResponseBody(context);
        }

    }

    /**
     * Runs the given logging action, reporting anything it throws on the logger of this provider and
     * swallowing it, so that logging a call can never be the reason it fails.
     *
     * @param action The logging action to run
     */
    protected void safely(Runnable action) {
        LoggingGuard.safely(log, "Unable to log the client call, the call itself is left unaffected", action);
    }

    /**
     * Creates the {@link LoggedBodyCapture} used to capture a request or response body.
     * <p>
     * Same extension point as {@link LoggedFilterConfiguration.Builder#bodyCapture} on the server side:
     * override to plug in a different body capture strategy.
     *
     * @param limit The maximum size of the body to capture in bytes, or {@code -1} for no limit
     * @return The body capture to use
     */
    protected LoggedBodyCapture createBodyCapture(int limit) {
        return new BoundedLoggedBodyCapture(limit);
    }

    /**
     * Indicates whether the lines this provider writes are enabled, bodies being neither captured nor
     * filtered for lines that are not.
     *
     * @return {@code true} if the log lines written by this provider are enabled, {@code false} otherwise
     */
    protected boolean isLoggingEnabled() {
        return log.isInfoEnabled();
    }

}
