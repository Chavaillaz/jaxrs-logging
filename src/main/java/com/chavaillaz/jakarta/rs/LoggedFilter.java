package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedField.DURATION;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_STATUS;
import static com.chavaillaz.jakarta.rs.internal.BodyCapturer.CAPTURE_FAILURE;
import static jakarta.ws.rs.Priorities.HEADER_DECORATOR;
import static jakarta.ws.rs.RuntimeType.SERVER;
import static java.lang.String.valueOf;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import jakarta.annotation.Priority;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.chavaillaz.jakarta.rs.LoggedBody.Direction;
import com.chavaillaz.jakarta.rs.LoggedBody.LogType;
import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import com.chavaillaz.jakarta.rs.LoggedResolver.BodyConfiguration;
import com.chavaillaz.jakarta.rs.client.LoggedClientFilter;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.BodyCapturer;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyConfiguration;
import com.chavaillaz.jakarta.rs.internal.LoggingGuard;

/**
 * Provider logging the requests received by the resources annotated with {@link Logged}, and describing each
 * of them in {@link MDC} for every line logged while it is processed (see {@link LoggedField}):
 * <ul>
 *     <li>Request identifier (from the {@value #REQUEST_ID_HEADER} header, or a random UUID)</li>
 *     <li>Request method and URI path relative to the base URI</li>
 *     <li>Request query parameters, credentials masked (see
 *     {@link LoggedFilterConfiguration.Builder#sensitiveParameters(java.util.function.BiPredicate)})</li>
 *     <li>Resource class and method matched by the request</li>
 *     <li>Whatever the {@link LoggedMapping} annotations of the resource ask for</li>
 * </ul>
 * Once the response is written, the request is logged as
 * <code>Processed [method] [URI] with status [status] in [duration]ms</code>, at a level derived from the
 * status (see {@link LoggedFilterConfiguration.Builder#responseLevel(java.util.function.IntFunction)}), with
 * the status, the duration and - if {@link LoggedBody} asks for them - the bodies in {@link MDC} as well.
 * <p>
 * Its low priority ({@link Priorities#HEADER_DECORATOR}) runs its filters first on the request and last on
 * the response, so the {@link MDC} entries it puts cover the other providers; a subclass can declare a
 * {@link Priority} of its own. Bodies are captured by {@link LoggedBodyInterceptor} instead, whose priority
 * places it after any entity coder, so it captures the entity rather than its transfer encoding: an
 * application registering its providers explicitly registers both.
 * <p>
 * The configuration resolved from the annotations of a resource method, and the body filters it names, are
 * cached for the lifetime of the provider, which suits the fixed set of resources of an application but not
 * one generating resource classes at runtime.
 */
@Logged
@Provider
@ConstrainedTo(SERVER)
@Priority(HEADER_DECORATOR)
public class LoggedFilter implements ContainerRequestFilter, ContainerResponseFilter, ReaderInterceptor, WriterInterceptor {

    /**
     * Logger the requests, their bodies and the failures to log them are written to.
     */
    protected static final Logger log = LoggerFactory.getLogger(LoggedFilter.class);

    /**
     * Name of the header carrying the request identifier, read from the requests received and set by
     * {@link LoggedClientFilter} on the calls made, so both sides of a call are logged under one identifier.
     */
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    /**
     * Configuration of this provider: the names of its MDC entries, how it identifies a request, which
     * parameters it keeps out of the logs, the level it logs a request at and how it captures bodies.
     */
    protected final LoggedFilterConfiguration configuration;

    /**
     * Resolves which {@link LoggedMapping} and {@link LoggedBody} configuration applies to the resource
     * method matched by the current request, caching results per resource, along with the
     * {@link LoggedBodyFilter} instances it names.
     */
    final LoggedResolver resolver = new LoggedResolver();

    /**
     * Provides access to the resource class and method matched by the current request.
     */
    @Context
    protected ResourceInfo resourceInfo;

    /**
     * The MDC entries of the requests this provider logs, named as configured.
     */
    private final RequestMdc mdc;

    /**
     * Describes the requests received, masking the query parameters the configuration reports as sensitive.
     */
    private final RequestDescriber describer;

    /**
     * Puts the MDC entries the {@link LoggedMapping} annotations ask for, keeping the parameters the
     * configuration reports as sensitive out of automatic mappings, as well as the names of the fields.
     */
    private final MappingApplier mappingApplier;

    /**
     * Captures the bodies of the requests read and the responses written, as the configuration says to.
     */
    private final BodyCapturer bodyCapturer;

    /**
     * Writes the lines logging the requests and returns their identifier to the caller, as configured.
     */
    private final ExchangeLogger exchangeLogger;

    /**
     * Creates a provider with the default configuration (see {@link LoggedFilterConfiguration#defaults()}).
     */
    public LoggedFilter() {
        this(LoggedFilterConfiguration.defaults());
    }

    /**
     * Creates a provider with the given configuration.
     * <p>
     * A container instantiates a provider through its no-argument constructor, so a subclass passes its
     * configuration from its own (see {@link LoggedFilterConfiguration}), while an application registering
     * its providers explicitly passes it here directly.
     *
     * @param configuration The configuration of the provider
     */
    public LoggedFilter(LoggedFilterConfiguration configuration) {
        this.configuration = requireNonNull(configuration, "The configuration is required");
        this.mdc = new RequestMdc(configuration.fieldNames());
        this.describer = new RequestDescriber(configuration::isSensitive);
        this.mappingApplier = new MappingApplier(configuration::isSensitive, mdc::isField);
        this.bodyCapturer = new BodyCapturer(log, configuration::createBodyCapture);
        this.exchangeLogger = new ExchangeLogger(log, configuration);
    }

    /**
     * Puts a diagnostic context value identified by the given key into the current thread's context map,
     * recording the entry against the request whose entries this thread carries, so it is removed once that
     * request has been fully processed, whichever thread completes it.
     * <p>
     * Every MDC entry set by this provider, or a subclass extending it, should go through this method
     * (or the {@link #putMdc(LoggedField, String)} overload) rather than {@link MDC#put(String, String)}
     * directly, to guarantee it does not outlive the request.
     *
     * @param key   The MDC key
     * @param value The value to be associated with the given key, ignored if {@code null} or blank
     */
    protected void putMdc(String key, @Nullable String value) {
        mdc.put(key, value);
    }

    /**
     * Puts a diagnostic context value identified by the given field into the current thread's context map,
     * unless the field is left out (see {@link LoggedFilterConfiguration.Builder#withoutField(LoggedField)}).
     *
     * @param field The field for which put the given value
     * @param value The value to be associated with the given field, ignored if {@code null} or blank
     */
    protected void putMdc(LoggedField field, @Nullable String value) {
        mdc.put(field, value);
    }

    /**
     * Gets a diagnostic context value identified by the given field from the current thread's context map.
     *
     * @param field The field for which get the value
     * @return The value associated with the given field, {@code null} for a field left out (see
     * {@link LoggedFilterConfiguration.Builder#withoutField(LoggedField)})
     */
    protected @Nullable String getMdc(LoggedField field) {
        return mdc.get(field);
    }

    /**
     * Replaces the control characters (e.g. CR, LF) of the given value with spaces.
     * <p>
     * Meant for the values a client controls (headers, query or path parameters) before they are put in MDC,
     * so a client cannot forge log lines with them. Bodies are logged as they are, line breaks included.
     *
     * @param value The value to sanitize
     * @return The sanitized value, or {@code null} if the given value was {@code null}
     */
    protected static @Nullable String sanitize(@Nullable String value) {
        return RequestDescriber.sanitize(value);
    }

    /**
     * Runs the given logging action, swallowing anything it throws, so that logging a request can never
     * be the reason it fails. See {@link LoggingGuard#safely(Logger, String, Runnable)}.
     *
     * @param action The logging action to run
     */
    private void safely(Runnable action) {
        LoggingGuard.safely(log, "Unable to log the request or response, the exchange itself is left unaffected", action);
    }

    /**
     * Gets the configuration a body about to be read or written is captured with, which is
     * {@link LoggedBodyConfiguration#NONE} when nothing is to be captured at all.
     * <p>
     * Guarded like the rest of the capture setup (see {@link BodyCapturer}): it runs before the entity is read
     * or written, which a configuration that cannot be resolved must not prevent.
     *
     * @param state  The state of the request, or {@code null} if this provider never saw it start
     * @param target The direction of the body about to be read or written
     * @return The configuration to capture the body with, never {@code null}
     */
    private LoggedBodyConfiguration getCaptureConfiguration(@Nullable LoggedRequestState state, Direction target) {
        // A request already completed has had its "Processed ..." line logged, so nothing captured from now
        // on - the later parts of a chunked or event stream response - could ever be logged
        if (!isLoggingEnabled() || (state != null && state.isCompleted())) {
            return LoggedBodyConfiguration.NONE;
        }
        return LoggingGuard.safely(log, CAPTURE_FAILURE, () -> getBodyConfiguration(state, target), LoggedBodyConfiguration.NONE);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Starts the request: attaches its state, puts the MDC entries describing it, and logs its
     * {@code "Received ..."} line right away when it has no entity to read. Nothing done here can fail the
     * request, which the resource method has yet to serve.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        safely(() -> {
            // Starts measuring the duration, and records this instance as the one handling the request, for
            // LoggedBodyInterceptor to hand its captures back to
            LoggedRequestState state = LoggedRequestState.of(requestContext, this);
            // From here on, the entries this thread carries are this request's
            mdc.start(state);

            putMdcFromRequest(state, requestContext);
            putMdcFromMappings(requestContext);

            // Without an entity to read, aroundReadFrom is never called
            if (getBodyConfiguration(state, REQUEST).logs(LOG) && !(requestContext.hasEntity() && requestContext.getLength() != 0)) {
                logRequest(state, EMPTY);
            }
        });
    }

    /**
     * Puts the MDC entries this provider always creates, describing the request itself and the resource
     * matched for it (see {@link RequestDescriber}).
     * <p>
     * Everything sourced from the request is passed through {@link #sanitize(String)} first, as all of it
     * is client-controlled - including the identifier the configuration gets for it, which is replaced with
     * a random one when there is none.
     * <p>
     * The state of the request keeps what the lines logging it show, which MDC may not carry.
     *
     * @param state          The state of the request received
     * @param requestContext The context of the request received
     */
    private void putMdcFromRequest(LoggedRequestState state, ContainerRequestContext requestContext) {
        // A strategy of the application failing costs the request its identifier, which is then generated,
        // rather than every field describing it
        String requestId = LoggingGuard.safely(log, "Unable to get the identifier of the request, a random one is used instead",
                () -> configuration.requestIdOf(requestContext), null);
        describer.describe(requestContext, resourceInfo, requestId, (field, value) -> {
            state.describe(field, value);
            putMdc(field, value);
        });
    }

    /**
     * Puts the MDC entries the {@link LoggedMapping} annotations of the matched resource method ask for,
     * following the rules of {@link MappingApplier}.
     *
     * @param requestContext The context of the request received
     */
    private void putMdcFromMappings(ContainerRequestContext requestContext) {
        mappingApplier.apply(resolver.getMappings(resourceInfo), type -> getParameters(requestContext, type), this::putMdc);
    }

    /**
     * Gets the parameters of the given request a mapping of the given type reads from.
     *
     * @param requestContext The context of the request received
     * @param type           The type of parameter to read
     * @return The parameters of that type, by name
     */
    private static Map<String, List<String>> getParameters(ContainerRequestContext requestContext, MappingType type) {
        return switch (type) {
            case PATH -> requestContext.getUriInfo().getPathParameters();
            case QUERY -> requestContext.getUriInfo().getQueryParameters();
            case HEADER -> requestContext.getHeaders();
        };
    }

    /**
     * Indicates whether the lines this provider writes are enabled, bodies being neither captured nor
     * filtered for lines that are not.
     * <p>
     * Checks {@code INFO}, the lowest level this provider writes at, rather than the level the request will
     * be logged at, which depends on a status unknown until the request is answered: an application logging
     * above {@code INFO} gets its failures logged without their bodies, rather than every body of every
     * request buffered in case it fails.
     *
     * @return {@code true} if the log lines written by this provider are enabled, {@code false} otherwise
     */
    private boolean isLoggingEnabled() {
        return log.isInfoEnabled();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Logs the {@code "Received ..."} line with the body captured, even when reading the entity failed.
     * Most JAX-RS implementations only call this when the resource method reads the entity: when it does not,
     * the line is logged without a body by {@link #filter(ContainerRequestContext, ContainerResponseContext)}
     * instead.
     */
    @Override
    public @Nullable Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        try {
            return context.proceed();
        } finally {
            safely(() -> {
                LoggedRequestState state = LoggedRequestState.find(context);
                String body = state == null ? null : state.getRequestBody();
                if (isNotBlank(body) && getBodyConfiguration(state, REQUEST).logs(LOG)) {
                    logRequest(state, body);
                }
            });
        }
    }

    /**
     * Captures the request body while the entity is being read, storing it as
     * {@link LoggedRequestState#getRequestBody()} for {@link #aroundReadFrom(ReaderInterceptorContext)} and
     * {@link #logResponse(LoggedRequestState, String)} to log.
     * <p>
     * Called by {@link LoggedBodyInterceptor}, which runs after any entity coder, so what is captured is the
     * entity rather than its transfer encoding.
     *
     * @param context The context of the entity being read
     * @return The entity read
     * @throws IOException              if an IO error arises while reading the entity
     * @throws WebApplicationException  if the entity cannot be read
     */
    @Nullable Object captureRequestBody(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        LoggedRequestState state = LoggedRequestState.find(context);
        // The state is only read once a body was captured, which the configuration rules out without one
        return bodyCapturer.read(context, getCaptureConfiguration(state, REQUEST), body -> state.setRequestBody(body));
    }

    /**
     * Logs the request received by the server, unless the line would repeat one already logged for it (see
     * {@link LoggedRequestState#markRequestLogged(boolean)}), as several callbacks can reach this for the same
     * request - one per read of its entity, for instance.
     *
     * @param state       The state of the request being logged, described already
     * @param requestBody The request body to be logged
     */
    private void logRequest(LoggedRequestState state, String requestBody) {
        if (state.markRequestLogged(isNotBlank(requestBody))) {
            exchangeLogger.received(state.getMethod(), state.getUri(), requestBody);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Describes the response, and completes the request right away when it has no entity to write. Nothing
     * done here can fail the response: the resource method has already served the request, and a 500 would
     * invite the client to repeat it.
     */
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        safely(() -> {
            LoggedRequestState state = startedState(requestContext);
            if (state != null) {
                mdc.onBehalfOf(state, () -> describeResponse(state, responseContext));
            }
        });
    }

    /**
     * Gets the state of the given request, first establishing it for a request whose start this provider
     * never saw: one aborted by a filter running earlier, as an authentication filter at
     * {@link Priorities#AUTHENTICATION} does, skips the remaining request filters but not the response filters.
     * Its duration then counts from this point.
     *
     * @param requestContext The context of the request received
     * @return The state of the request, or {@code null} if even establishing it failed
     */
    private @Nullable LoggedRequestState startedState(ContainerRequestContext requestContext) {
        LoggedRequestState state = LoggedRequestState.find(requestContext);
        if (state == null) {
            filter(requestContext);
            state = LoggedRequestState.find(requestContext);
        }
        return state;
    }

    /**
     * Describes the response in MDC, completing the request right away when it has no entity to write.
     *
     * @param state           The state of the request being answered
     * @param responseContext The context of the response to be sent
     */
    private void describeResponse(LoggedRequestState state, ContainerResponseContext responseContext) {
        try {
            // Kept first, for the request to be logged with its status whatever fails below
            state.setStatus(responseContext.getStatus());

            // A request whose resource method never read its entity has not been logged yet
            if (getBodyConfiguration(state, REQUEST).logs(LOG) && !state.isRequestLogged()) {
                logRequest(state, EMPTY);
            }

            putMdc(RESPONSE_STATUS, valueOf(state.getStatus()));
            exchangeLogger.returnRequestId(responseContext.getHeaders(), getMdc(REQUEST_ID));
        } finally {
            // Without an entity to write (204 No Content, HEAD), aroundWriteTo is never called: the request is
            // completed here whatever happened above, as completing it is what removes its MDC entries
            if (!responseContext.hasEntity()) {
                logResponse(state, EMPTY);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Completes the request once its entity is written, or failed to be, so it is logged either way and its
     * MDC entries do not stay behind on a pooled thread.
     */
    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        try {
            context.proceed();
        } finally {
            safely(() -> {
                LoggedRequestState state = LoggedRequestState.find(context);
                if (state != null) {
                    mdc.onBehalfOf(state, () -> logResponseWithBody(state));
                }
            });
        }
    }

    /**
     * Completes the request once its entity has been written, with the response body if it was captured.
     * <p>
     * An entity written in several parts - a chunked or event stream response - goes through the writer
     * interceptors once per part, and only the first of them completes the request: a later one putting its
     * body in MDC would leave it there, as there is no completion left to remove it.
     *
     * @param state The state of the request being answered
     */
    private void logResponseWithBody(LoggedRequestState state) {
        if (state.isCompleted()) {
            return;
        }
        try {
            LoggedBodyConfiguration bodyConfiguration = getBodyConfiguration(state, RESPONSE);
            String body = state.getResponseBody();
            if (bodyConfiguration.logs(LogType.MDC)) {
                putMdc(RESPONSE_BODY, body);
            }
            logResponse(state, bodyConfiguration.logs(LOG) ? requireNonNullElse(body, EMPTY) : EMPTY);
        } catch (RuntimeException e) {
            // Completing the request is what removes its MDC entries, so it must happen all the same
            logResponse(state, EMPTY);
            throw e;
        }
    }

    /**
     * Captures the response body while the entity is being written, storing it as
     * {@link LoggedRequestState#getResponseBody()} for {@link #aroundWriteTo(WriterInterceptorContext)} to log.
     * <p>
     * Called by {@link LoggedBodyInterceptor}, for the same reason as
     * {@link #captureRequestBody(ReaderInterceptorContext)}.
     *
     * @param context The context of the entity being written
     * @throws IOException              if an IO error arises while writing the entity
     * @throws WebApplicationException  if the entity cannot be written
     */
    void captureResponseBody(WriterInterceptorContext context) throws IOException, WebApplicationException {
        LoggedRequestState state = LoggedRequestState.find(context);
        // The state is only read once a body was captured, which the configuration rules out without one
        bodyCapturer.write(context, getCaptureConfiguration(state, RESPONSE), body -> state.setResponseBody(body));
    }

    /**
     * Logs the response sent by the server and completes the request (see
     * {@link LoggedRequestState#markCompleted()}), removing its MDC entries.
     * <p>
     * This is the single completion point of a request: idempotent, so the callbacks that can reach it do not
     * log the request twice, and unconditional, so its MDC entries are removed whatever else applies to it.
     * The duration is measured here, and therefore covers serializing and writing the entity.
     *
     * @param state        The state of the request being completed, its response described already
     * @param responseBody The response body to be logged
     */
    void logResponse(LoggedRequestState state, String responseBody) {
        if (!state.markCompleted()) {
            return;
        }

        try {
            long duration = state.getElapsedMillis();
            putMdc(DURATION, valueOf(duration));

            if (getBodyConfiguration(state, REQUEST).logs(LogType.MDC)) {
                putMdc(REQUEST_BODY, state.getRequestBody());
            }

            exchangeLogger.processed(state.getMethod(), state.getUri(), state.getStatus(), duration, responseBody);
        } finally {
            cleanupMdc(state);
        }
    }

    /**
     * Gets the body logging configuration for the given target (request or response) of the resource
     * method matched by the given request, delegating resolution and caching to {@link #resolver} the
     * first time it is asked for and reusing that result for the rest of the request afterwards (see
     * {@link LoggedRequestState#getBodyConfiguration()}).
     *
     * @param state  The state of the request, or {@code null} if this provider never saw it start
     * @param target The target for which to find the body logging configuration
     * @return The body logging configuration, {@link LoggedBodyConfiguration#NONE} for a request without
     * state, never {@code null}
     */
    LoggedBodyConfiguration getBodyConfiguration(@Nullable LoggedRequestState state, Direction target) {
        if (state == null) {
            // Nowhere to keep the resolved configuration, and nowhere to keep a body captured with it either
            return LoggedBodyConfiguration.NONE;
        }
        BodyConfiguration resolved = state.getBodyConfiguration();
        if (resolved == null) {
            resolved = resolver.getBodyConfiguration(resourceInfo);
            state.setBodyConfiguration(resolved);
        }
        return resolved.of(target);
    }

    /**
     * Removes from the current thread's context map every entry put through {@link #putMdc(String, String)}
     * for the given request (see {@link LoggedRequestState#getMdcEntries()}), which covers every entry put by
     * this provider itself as well as any subclass following the same convention.
     * <p>
     * Also sweeps the fields named by the configuration as a safety net, in case a subclass still puts one
     * of those directly through {@link MDC#put(String, String)} (as opposed to
     * {@link #putMdc(LoggedField, String)}).
     *
     * @param state The state of the request done with
     */
    void cleanupMdc(LoggedRequestState state) {
        mdc.cleanup(state);
    }

}
