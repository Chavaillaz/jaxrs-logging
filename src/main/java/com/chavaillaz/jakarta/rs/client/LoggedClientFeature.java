package com.chavaillaz.jakarta.rs.client;

import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedFeature.REQUEST_ID_HEADER;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static com.chavaillaz.jakarta.rs.LoggedSupport.levelOf;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.DEFAULT_LIMIT;
import static com.chavaillaz.jakarta.rs.filter.MaskingBodyFilter.DEFAULT_MASK;
import static com.chavaillaz.jakarta.rs.internal.Sanitizer.requestIdOf;
import static jakarta.ws.rs.Priorities.ENTITY_CODER;
import static jakarta.ws.rs.Priorities.HEADER_DECORATOR;
import static jakarta.ws.rs.RuntimeType.CLIENT;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

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
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import com.chavaillaz.jakarta.rs.LoggedBody;
import com.chavaillaz.jakarta.rs.LoggedFeature;
import com.chavaillaz.jakarta.rs.LoggedFeatureConfiguration;
import com.chavaillaz.jakarta.rs.LoggedField;
import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import com.chavaillaz.jakarta.rs.LoggedSupport;
import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.BodyCapturer;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyConfiguration;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyFilterFactory;
import com.chavaillaz.jakarta.rs.internal.LoggingGuard;

/**
 * Client-side counterpart of {@link LoggedFeature}, logging the calls made through a JAX-RS {@code Client},
 * the credentials their URI carries masked (see {@link Builder#sensitiveParameters(BiPredicate)}):
 * <ul>
 *     <li>{@code Calling [method] [uri]}, once the request is about to be sent</li>
 *     <li>{@code Called [method] [uri] with status [status] in [duration]ms}, once the response is received</li>
 * </ul>
 * It propagates the identifier of the current request (see {@link LoggedField#REQUEST_ID}) to the service
 * called, in its {@value LoggedFeature#REQUEST_ID_HEADER} header by default, so both sides of a call are logged
 * under one identifier: a call made outside of a request is given a random one, which the lines logging it carry.
 * It reads it from MDC on the thread running the filters, which an asynchronous call runs on the executor of the
 * client: that executor must propagate MDC (see
 * {@link com.chavaillaz.jakarta.rs.mdc.MdcPropagation#wrap(java.util.concurrent.ExecutorService)}).
 * <p>
 * Configured through {@link #builder()}, it applies to every call made through the {@code Client} or
 * {@code WebTarget} it is registered on, as a {@link Feature} registering the filter and the interceptor doing
 * the work (see {@link #configure(FeatureContext)}). It is no {@code @Provider}, as RESTEasy hands the providers
 * it discovers to every client of the deployment.
 * <p>
 * A body is logged on a line of its own, as a response body is only read if and when the calling code reads
 * the entity: nothing marks the end of a call that an MDC entry holding it could be scoped to, so only
 * {@link LoggedBody.LogType#LOG} is supported.
 */
@ConstrainedTo(CLIENT)
public final class LoggedClientFeature implements Feature {

    private static final Logger log = LoggerFactory.getLogger(LoggedClientFeature.class);

    /**
     * Name of the request property holding the moment the call started, to compute its duration.
     */
    private static final String REQUEST_TIME_PROPERTY = LoggedClientFeature.class.getName() + ".requestTime";

    /**
     * Name of the request property holding the method of the call, for the lines logging its bodies, which an
     * interceptor context does not give access to.
     */
    private static final String REQUEST_METHOD_PROPERTY = LoggedClientFeature.class.getName() + ".requestMethod";

    /**
     * Name of the request property holding the URI of the call as it is logged, for the same reason as
     * {@link #REQUEST_METHOD_PROPERTY}.
     */
    private static final String REQUEST_URI_PROPERTY = LoggedClientFeature.class.getName() + ".requestUri";

    /**
     * Name of the request property recording that the response body of the call has been logged, see
     * {@link #captureResponseBody(ReaderInterceptorContext)}.
     */
    private static final String RESPONSE_BODY_LOGGED_PROPERTY = LoggedClientFeature.class.getName() + ".responseBodyLogged";

    /**
     * Name of the request property holding the correlation identifier this feature gave the call, for the lines
     * logging it to carry, see {@link #withCorrelationId(Object, Runnable)}.
     */
    private static final String CORRELATION_ID_PROPERTY = LoggedClientFeature.class.getName() + ".correlationId";

    private final String correlationIdMdcKey;
    private final String correlationIdHeader;
    private final BiPredicate<MappingType, String> sensitiveParameters;
    private final IntFunction<@Nullable Level> responseLevel;
    private final LoggedBodyConfiguration requestBody;
    private final LoggedBodyConfiguration responseBody;
    private final BodyCapturer bodyCapturer;

    /**
     * Creates a client feature with the default configuration, for an application registering it by class: no
     * body logged, and the correlation identifier read from the {@code request-id} MDC key. Use
     * {@link #builder()} to configure it.
     */
    public LoggedClientFeature() {
        this(builder());
    }

    /**
     * Creates a client feature configured by the given builder.
     *
     * @param builder The builder holding the configuration
     */
    private LoggedClientFeature(Builder builder) {
        this.correlationIdMdcKey = builder.correlationIdMdcKey;
        this.correlationIdHeader = builder.correlationIdHeader;
        this.sensitiveParameters = builder.sensitiveParameters;
        this.responseLevel = builder.responseLevel;
        // Classes first, then instances, keeping the declaration order within each: a filter given as a
        // class cannot depend on one given as an instance without the caller having built both anyway
        Set<LoggedBodyFilter> filters = new LinkedHashSet<>(new LoggedBodyFilterFactory().getInstances(builder.bodyFilterClasses));
        filters.addAll(builder.bodyFilterInstances);
        Set<LoggedBodyFilter> bodyFilters = unmodifiableSet(filters);
        this.requestBody = bodyConfiguration(builder.logRequestBody, builder.requestBodyLimit, bodyFilters);
        this.responseBody = bodyConfiguration(builder.logResponseBody, builder.responseBodyLimit, bodyFilters);
        this.bodyCapturer = new BodyCapturer(log, builder.bodyCapture);
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
     * Creates a new builder to configure a {@link LoggedClientFeature} instance.
     *
     * @return The builder created
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder of {@link LoggedClientFeature}, starting from the default of every setting.
     */
    public static final class Builder {

        private String correlationIdMdcKey = REQUEST_ID.getDefaultField();
        private String correlationIdHeader = REQUEST_ID_HEADER;
        private BiPredicate<MappingType, String> sensitiveParameters = LoggedFeatureConfiguration::isCredential;
        private IntFunction<@Nullable Level> responseLevel = LoggedSupport::levelOf;
        private boolean logRequestBody = false;
        private boolean logResponseBody = false;
        private int requestBodyLimit = DEFAULT_LIMIT;
        private int responseBodyLimit = DEFAULT_LIMIT;
        private final Set<Class<? extends LoggedBodyFilter>> bodyFilterClasses = new LinkedHashSet<>();
        private final Set<LoggedBodyFilter> bodyFilterInstances = new LinkedHashSet<>();
        private IntFunction<LoggedBodyCapture> bodyCapture = BoundedLoggedBodyCapture::new;

        private Builder() {
            // Created through LoggedClientFeature.builder()
        }

        /**
         * Sets the MDC key the identifier propagated to the services called is read from, a random one being
         * sent when the entry is absent or blank. Defaults to {@code request-id}, to change along with a renamed
         * {@link LoggedField#REQUEST_ID} (see {@link LoggedFeatureConfiguration.Builder#fieldName(LoggedField, String)}).
         * The identifier is sanitized and truncated to 128 characters, as a {@link LoggedFeature} does one it
         * receives.
         *
         * @param mdcKey The MDC key to read the correlation identifier from
         * @return This builder
         * @throws IllegalArgumentException if the key is blank, as no field of a {@link LoggedFeature} is
         */
        public Builder correlationIdKey(String mdcKey) {
            if (isBlank(mdcKey)) {
                throw new IllegalArgumentException("The MDC key of the correlation identifier is required");
            }
            this.correlationIdMdcKey = mdcKey;
            return this;
        }

        /**
         * Sets the header the correlation identifier is propagated in, to match the one the services called
         * read theirs from (see {@link LoggedFeatureConfiguration.Builder#requestIdHeader(String)}). Defaults to
         * {@value LoggedFeature#REQUEST_ID_HEADER}.
         * <p>
         * A call already carrying the header, in any casing, keeps the value the calling code gave it.
         *
         * @param header The name of the header
         * @return This builder
         * @throws IllegalArgumentException if the name is blank
         */
        public Builder correlationIdHeader(String header) {
            if (isBlank(header)) {
                throw new IllegalArgumentException("The name of the correlation identifier header is required");
            }
            this.correlationIdHeader = header;
            return this;
        }

        /**
         * Sets which query parameters of the calls have their value masked, as
         * {@link LoggedFeatureConfiguration.Builder#sensitiveParameters(BiPredicate)} does for the requests
         * received, so both can be given the same predicate, asked about the {@link MappingType#QUERY} parameters
         * of each call by their decoded name. Defaults to {@link LoggedFeatureConfiguration#isCredential}, to
         * compose with, for example to mask the key a partner API expects in its query string:
         * <pre>{@code
         * .sensitiveParameters((type, name) -> isCredential(type, name) || "partner-key".equalsIgnoreCase(name))
         * }</pre>
         * The user information of a URI ({@code https://user:secret@host}) is masked whatever the predicate says.
         *
         * @param predicate Whether the value of the parameter of the given type and name must be kept out of the logs
         * @return This builder
         */
        public Builder sensitiveParameters(BiPredicate<MappingType, String> predicate) {
            this.sensitiveParameters = requireNonNull(predicate, "The sensitive parameters predicate is required");
            return this;
        }

        /**
         * Sets the level a call is logged at once answered, given its status, for example to leave a {@code 404}
         * the calling code expects at {@code INFO}, as
         * {@link LoggedFeatureConfiguration.Builder#responseLevel(IntFunction)} does for the requests received. A
         * status the function returns {@code null} for, or fails for, is logged at its default level. Defaults to
         * {@link LoggedSupport#levelOf(int)}.
         *
         * @param levels The level to log a call answered with the given status at
         * @return This builder
         */
        public Builder responseLevel(IntFunction<@Nullable Level> levels) {
            this.responseLevel = requireNonNull(levels, "The response level function is required");
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
         * Sets the size limit in bytes applied to both the request and response body when logged, which defaults
         * to {@link LoggedBodyCapture#DEFAULT_LIMIT}, 64 KiB.
         *
         * @param limit The maximum size of the body to be logged in bytes, or {@code -1} for no limit
         * @return This builder
         * @throws IllegalArgumentException if the limit is lower than {@code -1}
         */
        public Builder bodyLimit(int limit) {
            return requestBodyLimit(limit).responseBodyLimit(limit);
        }

        /**
         * Sets the size limit in bytes applied to the request body when logged, which defaults to
         * {@link LoggedBodyCapture#DEFAULT_LIMIT}, 64 KiB.
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
         * Sets the size limit in bytes applied to the response body when logged, which defaults to
         * {@link LoggedBodyCapture#DEFAULT_LIMIT}, 64 KiB.
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
         * Adds filters applied to the bodies of both directions before they are logged, in the order given.
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
         * Adds filters applied to the bodies of both directions before they are logged, in the order given, after
         * those given as classes. Instances, such as {@code new JsonMaskingBodyFilter("password")}, need no
         * subclass fixing their arguments, as the classes {@link LoggedBody#filters()} takes do.
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
         * Sets how the bodies are captured, given the maximum size to capture in bytes, or {@code -1} for no
         * limit, as {@link LoggedFeatureConfiguration.Builder#bodyCapture(IntFunction)} does for the requests
         * received. Defaults to capturing them in memory ({@link BoundedLoggedBodyCapture}).
         *
         * @param factory The creation of the capture of a body keeping at most the given number of bytes
         * @return This builder
         */
        public Builder bodyCapture(IntFunction<LoggedBodyCapture> factory) {
            this.bodyCapture = requireNonNull(factory, "The body capture factory is required");
            return this;
        }

        /**
         * Builds the {@link LoggedClientFeature} configured by this builder.
         *
         * @return The client feature created
         */
        public LoggedClientFeature build() {
            return new LoggedClientFeature(this);
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
     * Propagates the correlation identifier and logs the {@code "Calling ..."} line, as the filter this feature
     * registers sees the request of a call. Nothing done here fails the call.
     *
     * @param requestContext The context of the request about to be sent
     */
    void filter(ClientRequestContext requestContext) {
        // Guarded apart, so a call that cannot be described still carries the identifier
        safely(() -> propagateCorrelationId(requestContext));
        safely(() -> {
            requestContext.setProperty(REQUEST_TIME_PROPERTY, nanoTime());
            String uri = getLoggedUri(requestContext.getUri());
            requestContext.setProperty(REQUEST_METHOD_PROPERTY, requestContext.getMethod());
            requestContext.setProperty(REQUEST_URI_PROPERTY, uri);
            withCorrelationId(requestContext.getProperty(CORRELATION_ID_PROPERTY),
                    () -> log.info("Calling {} {}", requestContext.getMethod(), uri));
        });
    }

    /**
     * Sets the correlation identifier on the given request, unless it already carries one.
     *
     * @param requestContext The context of the request about to be sent
     */
    private void propagateCorrelationId(ClientRequestContext requestContext) {
        // Compared without regard to case, as the headers of a client request may be a case-sensitive map
        if (requestContext.getHeaders().keySet().stream().noneMatch(correlationIdHeader::equalsIgnoreCase)) {
            // Sanitized as a LoggedFeature does one it receives: a control character in a header fails the call in
            // the HTTP client of the JDK, and a blank identifier correlates nothing
            String correlationId = requestIdOf(MDC.get(correlationIdMdcKey));
            requestContext.getHeaders().putSingle(correlationIdHeader, correlationId);
            requestContext.setProperty(CORRELATION_ID_PROPERTY, correlationId);
        }
    }

    /**
     * Logs a line of a call under the correlation identifier this feature gave it, when the current thread has
     * none in MDC: a call made outside of a request, or on the executor of an asynchronous one, is given a random
     * identifier, the only one the service called logs it under.
     *
     * @param correlationId The correlation identifier this feature gave the call, {@code null} if the calling code
     *                      gave it one
     * @param logging       The logging of the line
     */
    private void withCorrelationId(@Nullable Object correlationId, Runnable logging) {
        String current = MDC.get(correlationIdMdcKey);
        if (!(correlationId instanceof String id) || isNotBlank(current)) {
            logging.run();
            return;
        }
        MDC.put(correlationIdMdcKey, id);
        try {
            logging.run();
        } finally {
            if (current == null) {
                MDC.remove(correlationIdMdcKey);
            } else {
                MDC.put(correlationIdMdcKey, current);
            }
        }
    }

    /**
     * Renders the given URI as it is logged, the credentials it carries masked: its user information as a
     * whole ({@code https://user:secret@host}), and the value of the query parameters the configuration reports as
     * sensitive (see {@link Builder#sensitiveParameters(BiPredicate)}), their name visible. The rest is left as
     * given.
     *
     * @param uri The URI of the call
     * @return The URI as it must be logged
     */
    String getLoggedUri(URI uri) {
        String authority = uri.getRawAuthority();
        // Found at the last '@' of the authority rather than through getRawUserInfo(), null for an authority
        // java.net.URI cannot parse as a host and a port, a host name with an underscore as containers have
        int userInfoEnd = authority == null ? -1 : authority.lastIndexOf('@');
        if (uri.isOpaque() || (userInfoEnd < 0 && uri.getRawQuery() == null)) {
            // Nothing to mask, as for almost every call
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
     * Masks the value of the parameters of the given raw query the configuration reports as sensitive.
     *
     * @param rawQuery The query of a URI, still encoded
     * @return The query with the values of its sensitive parameters masked
     */
    private String maskQuery(String rawQuery) {
        return stream(rawQuery.split("&", -1))
                .map(parameter -> {
                    int separator = parameter.indexOf('=');
                    // Decoded to be compared, which cannot fail on the raw query of a java.net.URI
                    return separator >= 0 && sensitiveParameters.test(QUERY, URLDecoder.decode(parameter.substring(0, separator), UTF_8))
                            ? parameter.substring(0, separator + 1) + DEFAULT_MASK
                            : parameter;
                })
                .collect(joining("&"));
    }

    /**
     * Captures and logs the request body as the entity is written, as far as it was when writing it failed.
     *
     * @param context The context of the entity being written
     * @throws IOException             if an IO error arises while writing the entity
     * @throws WebApplicationException if the entity cannot be written
     */
    void captureRequestBody(WriterInterceptorContext context) throws IOException, WebApplicationException {
        bodyCapturer.write(context, isLoggingEnabled() ? requestBody : LoggedBodyConfiguration.NONE, body -> {
            if (isNotBlank(body)) {
                withCorrelationId(context.getProperty(CORRELATION_ID_PROPERTY), () -> log.info("Request body {} {}{}{}",
                        context.getProperty(REQUEST_METHOD_PROPERTY),
                        context.getProperty(REQUEST_URI_PROPERTY),
                        LF,
                        body));
            }
        });
    }

    /**
     * Logs the {@code "Called ..."} line, at the level the configuration gives the status, as the filter this
     * feature registers sees the response of a call. Nothing done here fails the call. A call a filter aborted
     * before this one ({@link ClientRequestContext#abortWith}) is logged with a zero duration.
     *
     * @param requestContext  The context of the request sent
     * @param responseContext The context of the response received
     */
    void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
        safely(() -> {
            long now = nanoTime();
            long start = requestContext.getProperty(REQUEST_TIME_PROPERTY) instanceof Long started ? started : now;
            long duration = NANOSECONDS.toMillis(now - start);
            int status = responseContext.getStatus();

            withCorrelationId(requestContext.getProperty(CORRELATION_ID_PROPERTY), () -> log.atLevel(responseLevel(status))
                    .log("Called {} {} with status {} in {}ms",
                            requestContext.getMethod(),
                            getLoggedUri(requestContext.getUri()),
                            status,
                            duration));
        });
    }

    /**
     * Gets the level a call answered with the given status is logged at: the one the configuration gives, or the
     * default one of the status when it gives none, or fails.
     *
     * @param status The status of the response received
     * @return The level to log the call at
     */
    private Level responseLevel(int status) {
        Level level = LoggingGuard.safely(log, "Unable to get the level of the client call, its default one is used instead",
                () -> responseLevel.apply(status), null);
        return level == null ? levelOf(status) : level;
    }

    /**
     * Captures and logs the response body as the calling code reads the entity, never if it does not. A
     * buffered entity read again is not logged again (see {@link #RESPONSE_BODY_LOGGED_PROPERTY}), and one read
     * as a stream is logged once the calling code read it to its end, or closed it.
     *
     * @param context The context of the entity being read
     * @return The entity read
     * @throws IOException             if an IO error arises while reading the entity
     * @throws WebApplicationException if the entity cannot be read
     */
    @Nullable Object captureResponseBody(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        boolean alreadyLogged = Boolean.TRUE.equals(context.getProperty(RESPONSE_BODY_LOGGED_PROPERTY));
        LoggedBodyConfiguration configuration = isLoggingEnabled() && !alreadyLogged ? responseBody : LoggedBodyConfiguration.NONE;
        return bodyCapturer.read(context, configuration, body -> {
            if (isNotBlank(body)) {
                context.setProperty(RESPONSE_BODY_LOGGED_PROPERTY, true);
                withCorrelationId(context.getProperty(CORRELATION_ID_PROPERTY), () -> log.info("Response body {} {}{}{}",
                        context.getProperty(REQUEST_METHOD_PROPERTY),
                        context.getProperty(REQUEST_URI_PROPERTY),
                        LF,
                        body));
            }
        }, handOver -> {
            // Nothing marks the end of a call: a response read as a stream is logged once that stream ends
        });
    }

    /**
     * {@inheritDoc}
     * <p>
     * Registers the filter logging the calls, at {@link Priorities#HEADER_DECORATOR}, before the filters of the
     * application on the request and after them on the response, and the interceptor capturing their bodies
     * after any entity coder, which both call this feature back.
     */
    @Override
    public boolean configure(FeatureContext context) {
        context.register(new CallFilter(this));
        context.register(new BodyInterceptor(this));
        return true;
    }

    /**
     * Logs the calls for the feature that registered it, which implements no filter contract itself: Jersey and
     * RESTEasy would call such a feature as a filter too, next to this one.
     */
    @ConstrainedTo(CLIENT)
    @Priority(HEADER_DECORATOR)
    private static final class CallFilter implements ClientRequestFilter, ClientResponseFilter {

        private final LoggedClientFeature feature;

        private CallFilter(LoggedClientFeature feature) {
            this.feature = feature;
        }

        @Override
        public void filter(ClientRequestContext requestContext) {
            feature.filter(requestContext);
        }

        @Override
        public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
            feature.filter(requestContext, responseContext);
        }

    }

    /**
     * Captures the bodies for the feature that registered it, after any entity coder
     * ({@link Priorities#ENTITY_CODER}), so a {@code Content-Encoding: gzip} body is logged as the payload rather
     * than compressed.
     */
    @ConstrainedTo(CLIENT)
    @Priority(ENTITY_CODER + 100)
    private static final class BodyInterceptor implements ReaderInterceptor, WriterInterceptor {

        private final LoggedClientFeature feature;

        private BodyInterceptor(LoggedClientFeature feature) {
            this.feature = feature;
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
            feature.captureRequestBody(context);
        }

        @Override
        public @Nullable Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
            return feature.captureResponseBody(context);
        }

    }

    /**
     * Runs the given logging action, reporting anything it throws and swallowing it, so logging a call is never
     * the reason it fails.
     *
     * @param action The logging action to run
     */
    private static void safely(Runnable action) {
        LoggingGuard.safely(log, "Unable to log the client call, the call itself is left unaffected", action);
    }

    /**
     * Indicates whether the lines of this feature are enabled, bodies being neither captured nor filtered for
     * lines that are not.
     *
     * @return {@code true} if the lines of this feature are enabled, {@code false} otherwise
     */
    private static boolean isLoggingEnabled() {
        return log.isInfoEnabled();
    }

}
