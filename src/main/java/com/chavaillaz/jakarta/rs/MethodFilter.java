package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedFeature.log;
import static com.chavaillaz.jakarta.rs.LoggedField.DURATION;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_STATUS;
import static com.chavaillaz.jakarta.rs.internal.Sanitizer.sanitize;
import static jakarta.ws.rs.HttpMethod.HEAD;
import static jakarta.ws.rs.RuntimeType.SERVER;
import static java.lang.String.valueOf;
import static java.util.Objects.requireNonNullElse;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.InterceptorContext;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.rs.LoggedBody.LogType;
import com.chavaillaz.jakarta.rs.LoggedFeature.Setup;
import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import com.chavaillaz.jakarta.rs.LoggedResolver.MethodConfiguration;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyConfiguration;
import com.chavaillaz.jakarta.rs.internal.LoggingGuard;

/**
 * Logs the requests of one resource method, for the {@link LoggedFeature} that registered it, as the
 * configuration it resolved for the method says.
 * <p>
 * The request filter starts a request - describes it in MDC, and logs it as received right away when it has
 * no entity to read. The reader interceptor logs it as received with its body once read. The response filter
 * describes the response, and completes the request when there is no entity to write, the writer interceptor
 * completing it once written otherwise: completing a request logs it as processed and removes its MDC entries.
 * A request aborted by a filter running earlier, as an authentication filter does, reaches the response filter
 * alone, which starts it then.
 * <p>
 * Nothing done here fails a request, every failure being reported instead, and the requests another feature
 * logs are left to it (see {@link LoggedRequestState}).
 */
@ConstrainedTo(SERVER)
final class MethodFilter implements ContainerRequestFilter, ContainerResponseFilter, ReaderInterceptor, WriterInterceptor {

    private final LoggedFeature feature;
    private final MethodConfiguration method;

    /**
     * Provides the context resolvers the feature looks its configuration up with, when the runtime injects them
     * here rather than into the feature.
     */
    @Context
    @Nullable Providers providers;

    /**
     * Creates the filter logging the requests of a resource method.
     *
     * @param feature The feature registering the filter
     * @param method  The configuration of the resource method
     */
    MethodFilter(LoggedFeature feature, MethodConfiguration method) {
        this.feature = feature;
        this.method = method;
    }

    /**
     * Gets the configuration of the resource method this filter logs the requests of.
     *
     * @return The configuration of the resource method
     */
    MethodConfiguration method() {
        return method;
    }

    /**
     * Indicates whether this filter captures a body, in which case it needs a {@link BodyInterceptor}.
     *
     * @return {@code true} if a body is captured, {@code false} otherwise
     */
    boolean capturesBodies() {
        return method.capturesBodies();
    }

    /**
     * Gets what the feature works with.
     *
     * @return What the feature works with
     */
    private Setup setup() {
        return feature.setup(providers);
    }

    /**
     * Runs the given logging action, reporting and swallowing anything it throws, so logging a request is
     * never the reason it fails.
     *
     * @param action The logging action to run
     */
    private static void safely(Runnable action) {
        LoggingGuard.safely(log, "Unable to log the request or response, the exchange itself is left unaffected", action);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Starts the request, unless the filter of another feature did.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        safely(() -> {
            if (LoggedRequestState.find(requestContext) == null) {
                start(requestContext);
            }
        });
    }

    /**
     * Starts the given request: attaches its state, puts the MDC entries describing it - those of the
     * application included (see {@link LoggedFeatureConfiguration.Builder#mdcEntries}) - and logs it as received
     * right away when it has no entity to read.
     *
     * @param requestContext The context of the request received
     */
    private void start(ContainerRequestContext requestContext) {
        Setup setup = setup();
        // Starts measuring the duration, and records this filter as the one logging the request
        LoggedRequestState state = LoggedRequestState.attach(requestContext, this);
        // From here on, the entries this thread carries are this request's
        setup.mdc().start(state);

        putMdcFromRequest(setup, state, requestContext);
        setup.mappingApplier().apply(method.mappings(), type -> parameters(requestContext, type), setup.mdc()::put);
        LoggingGuard.safely(log, "Unable to describe the request as the application asks, its own entries are left out",
                () -> setup.configuration().mdcEntriesOf(requestContext, method.resource())
                        .forEach((key, value) -> setup.mdc().put(key, sanitize(value))));

        // Without an entity to read, aroundReadFrom is never called
        if (method.requestBody().logs(LOG) && !(requestContext.hasEntity() && requestContext.getLength() != 0)) {
            logRequest(setup, state, EMPTY);
        }
    }

    /**
     * Puts the MDC entries describing the request and the resource method matched for it (see
     * {@link RequestDescriber}), and keeps on its state what the lines logging it show.
     *
     * @param setup          What the feature works with
     * @param state          The state of the request received
     * @param requestContext The context of the request received
     */
    private void putMdcFromRequest(Setup setup, LoggedRequestState state, ContainerRequestContext requestContext) {
        // A strategy of the application failing costs the request its identifier, generated instead
        String requestId = LoggingGuard.safely(log, "Unable to get the identifier of the request, a random one is used instead",
                () -> setup.configuration().requestIdOf(requestContext), null);
        setup.describer().describe(requestContext, method.resource(), requestId, (field, value) -> {
            state.describe(field, value);
            setup.mdc().put(field, value);
        });
    }

    /**
     * Gets the parameters of the given request a mapping of the given type reads.
     *
     * @param requestContext The context of the request received
     * @param type           The type of parameter to read
     * @return The parameters of that type, by name
     */
    private static Map<String, List<String>> parameters(ContainerRequestContext requestContext, MappingType type) {
        return switch (type) {
            case PATH -> requestContext.getUriInfo().getPathParameters();
            case QUERY -> requestContext.getUriInfo().getQueryParameters();
            case HEADER -> requestContext.getHeaders();
        };
    }

    /**
     * Gets the state of the request carried by the given context, if this filter logs it.
     *
     * @param context The context of the entity being read or written
     * @return The state of the request, {@code null} if another filter logs it, or none
     */
    @Nullable LoggedRequestState handledState(InterceptorContext context) {
        LoggedRequestState state = LoggedRequestState.find(context);
        return state != null && state.getFilter() == this ? state : null;
    }

    /**
     * Indicates whether the lines of the feature are enabled, bodies being neither captured nor filtered for
     * lines that are not. It checks {@code INFO}, the lowest level they are written at, as the level of a request
     * depends on a status unknown until it is answered.
     *
     * @return {@code true} if the lines of the feature are enabled, {@code false} otherwise
     */
    private static boolean isLoggingEnabled() {
        return log.isInfoEnabled();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Logs the request as received with its body once read, even when reading it failed. A body the resource
     * method reads as a stream, or never reads, is logged by the response filter instead.
     */
    @Override
    public @Nullable Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        try {
            return context.proceed();
        } finally {
            safely(() -> {
                LoggedRequestState state = handledState(context);
                String body = state == null ? null : state.getRequestBody();
                if (state != null && isNotBlank(body) && method.requestBody().logs(LOG)) {
                    logRequest(setup(), state, body);
                }
            });
        }
    }

    /**
     * Captures the request body while the entity is read, for {@link #aroundReadFrom} to log. A body the
     * resource method reads as a stream is handed over once the stream ends, or once the request is answered
     * (see {@link LoggedRequestState#endStreamedRequestBody()}).
     *
     * @param context The context of the entity being read
     * @param state   The state of the request
     * @return The entity read
     * @throws IOException             if an IO error arises while reading the entity
     * @throws WebApplicationException if the entity cannot be read
     */
    @Nullable Object captureRequestBody(ReaderInterceptorContext context, LoggedRequestState state) throws IOException, WebApplicationException {
        return setup().bodyCapturer().read(context, captureConfiguration(state, method.requestBody()),
                state::setRequestBody, state::setStreamedRequestBody);
    }

    /**
     * Gets the configuration a body about to be read or written is captured with.
     *
     * @param state         The state of the request
     * @param configuration The body logging configuration of the direction
     * @return The configuration, {@link LoggedBodyConfiguration#NONE} when nothing is captured
     */
    private static LoggedBodyConfiguration captureConfiguration(LoggedRequestState state, LoggedBodyConfiguration configuration) {
        // A request already completed has been logged, so what is written from now on - the later parts of a
        // chunked or event stream response - could never be
        return isLoggingEnabled() && !state.isCompleted() ? configuration : LoggedBodyConfiguration.NONE;
    }

    /**
     * Logs the request as received, unless the line would repeat one logged already (see
     * {@link LoggedRequestState#markRequestLogged(boolean)}), as an entity read twice reaches this twice.
     *
     * @param setup       What the feature works with
     * @param state       The state of the request, described already
     * @param requestBody The request body to log, blank if none
     */
    private static void logRequest(Setup setup, LoggedRequestState state, String requestBody) {
        if (state.markRequestLogged(isNotBlank(requestBody))) {
            setup.exchangeLogger().received(state.getMethod(), state.getUri(), requestBody);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Describes the response, and completes the request when it has no entity to write. Nothing done here fails
     * the response: the resource method has served the request, and a 500 would invite the client to repeat it.
     */
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        safely(() -> {
            LoggedRequestState state = startedState(requestContext);
            if (state != null) {
                setup().mdc().onBehalfOf(state, () -> describeResponse(state, requestContext, responseContext));
            }
        });
    }

    /**
     * Gets the state of the given request if this filter logs it, starting the request first when no request
     * filter did: a filter running earlier aborted it, which skips the remaining request filters but not the
     * response filters. Its duration counts from here then.
     *
     * @param requestContext The context of the request received
     * @return The state of the request, {@code null} if another filter logs it, or starting it failed
     */
    private @Nullable LoggedRequestState startedState(ContainerRequestContext requestContext) {
        LoggedRequestState state = LoggedRequestState.find(requestContext);
        if (state == null) {
            safely(() -> start(requestContext));
            state = LoggedRequestState.find(requestContext);
        }
        return state != null && state.getFilter() == this ? state : null;
    }

    /**
     * Describes the response in MDC, returns the request identifier to the caller, and completes the request
     * when no entity is written: the response has none, or answers a HEAD request.
     *
     * @param state           The state of the request answered
     * @param requestContext  The context of the request answered
     * @param responseContext The context of the response to send
     */
    private void describeResponse(LoggedRequestState state, ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        Setup setup = setup();
        try {
            // First, for the request to be logged with its status whatever fails below
            state.setStatus(responseContext.getStatus());

            // A request whose resource method read its entity as a stream, or never read it, is logged now, with
            // as much of its body as the method read
            state.endStreamedRequestBody();
            if (method.requestBody().logs(LOG)) {
                logRequest(setup, state, requireNonNullElse(state.getRequestBody(), EMPTY));
            }

            setup.mdc().put(RESPONSE_STATUS, valueOf(state.getStatus()));
            setup.exchangeLogger().returnRequestId(responseContext.getHeaders(), setup.mdc().recorded(state, REQUEST_ID));
        } finally {
            // Without an entity to write, aroundWriteTo is never called: the request is completed here whatever
            // happened above, as completing it removes its MDC entries. A HEAD request is answered with the entity
            // of its GET method, which Quarkus never writes
            if (!responseContext.hasEntity() || HEAD.equals(requestContext.getMethod())) {
                logResponse(state, EMPTY);
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Completes the request once its entity is written, or failed to be, so it is logged either way and its MDC
     * entries do not stay on a pooled thread.
     */
    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        try {
            context.proceed();
        } finally {
            safely(() -> {
                LoggedRequestState state = handledState(context);
                if (state != null) {
                    setup().mdc().onBehalfOf(state, () -> logResponseWithBody(state));
                }
            });
        }
    }

    /**
     * Completes the request once its entity is written, with the response body if it was captured.
     * <p>
     * An entity written in parts - a chunked or event stream response - goes through the writer interceptors
     * once per part, and only the first completes the request: a later one putting its body in MDC would leave
     * it there, with no completion left to remove it.
     *
     * @param state The state of the request answered
     */
    private void logResponseWithBody(LoggedRequestState state) {
        if (state.isCompleted()) {
            return;
        }
        try {
            String body = state.getResponseBody();
            if (method.responseBody().logs(LogType.MDC)) {
                setup().mdc().put(RESPONSE_BODY, body);
            }
            logResponse(state, method.responseBody().logs(LOG) ? requireNonNullElse(body, EMPTY) : EMPTY);
        } catch (RuntimeException e) {
            // Completing the request is what removes its MDC entries, so it must happen all the same
            logResponse(state, EMPTY);
            throw e;
        }
    }

    /**
     * Captures the response body while the entity is written, for {@link #aroundWriteTo} to log.
     *
     * @param context The context of the entity being written
     * @param state   The state of the request
     * @throws IOException             if an IO error arises while writing the entity
     * @throws WebApplicationException if the entity cannot be written
     */
    void captureResponseBody(WriterInterceptorContext context, LoggedRequestState state) throws IOException, WebApplicationException {
        setup().bodyCapturer().write(context, captureConfiguration(state, method.responseBody()), state::setResponseBody);
    }

    /**
     * Completes the request: logs it as processed, removes its MDC entries and releases its bodies. The single
     * completion point of a request, idempotent, so the callbacks reaching it log the request once, and
     * unconditional, so its MDC entries are removed whatever else applies to it. The duration is measured here,
     * and covers writing the entity.
     *
     * @param state        The state of the request, its response described already
     * @param responseBody The response body to log, blank if none
     */
    void logResponse(LoggedRequestState state, String responseBody) {
        if (!state.markCompleted()) {
            return;
        }
        Setup setup = setup();
        try {
            long duration = state.getElapsedMillis();
            setup.mdc().put(DURATION, valueOf(duration));
            // Handed over when the response was described, unless that never happened
            state.endStreamedRequestBody();
            if (method.requestBody().logs(LogType.MDC)) {
                setup.mdc().put(REQUEST_BODY, state.getRequestBody());
            }
            setup.exchangeLogger().processed(state.getMethod(), state.getUri(), state.getStatus(), duration, responseBody);
        } finally {
            setup.mdc().cleanup(state);
            state.releaseBodies();
        }
    }

    /**
     * Captures the bodies of the requests a {@link MethodFilter} logs, from after any entity coder
     * ({@link Priorities#ENTITY_CODER}): at the priority of the filter, which runs it before the coder, the body
     * of a {@code Content-Encoding: gzip} request would be captured compressed.
     */
    @ConstrainedTo(SERVER)
    static final class BodyInterceptor implements ReaderInterceptor, WriterInterceptor {

        private final MethodFilter filter;

        /**
         * Creates the interceptor capturing the bodies of the requests the given filter logs.
         *
         * @param filter The filter the bodies are captured for
         */
        BodyInterceptor(MethodFilter filter) {
            this.filter = filter;
        }

        @Override
        public @Nullable Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
            LoggedRequestState state = filter.handledState(context);
            return state == null ? context.proceed() : filter.captureRequestBody(context, state);
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
            LoggedRequestState state = filter.handledState(context);
            if (state == null) {
                context.proceed();
            } else {
                filter.captureResponseBody(context, state);
            }
        }

    }

}
