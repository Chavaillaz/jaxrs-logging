package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedField.DURATION;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_STATUS;
import static jakarta.ws.rs.RuntimeType.SERVER;
import static java.lang.String.valueOf;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.chavaillaz.jakarta.rs.LoggedBody.Direction;
import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import com.chavaillaz.jakarta.rs.LoggedResolver.BodyConfiguration;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Provider adding the following request information to {@link MDC} (see {@link LoggedField}):
 * <ul>
 *     <li>Request identifier (from the {@value #REQUEST_ID_HEADER} header, or a random UUID)</li>
 *     <li>Request method (see {@link jakarta.ws.rs.HttpMethod})</li>
 *     <li>Request URI path relative to the base URI</li>
 *     <li>Request query parameters, credentials masked (see
 *     {@link LoggedFilterConfiguration.Builder#sensitiveParameters(java.util.function.BiPredicate)})</li>
 *     <li>Resource class matched by the current request</li>
 *     <li>Resource method matched by the current request</li>
 *     <li>Whatever the {@link LoggedMapping} annotations of the resource ask for</li>
 * </ul>
 * Once the response is computed, the request will be logged using the format
 * <code>Processed [method] [URI] with status [status] in [duration]ms</code>, at a level derived from the
 * status (see {@link LoggedFilterConfiguration.Builder#responseLevel(java.util.function.IntFunction)}), with the
 * following {@link MDC}:
 * <ul>
 *     <li>Response status (see {@link jakarta.ws.rs.core.Response.Status})</li>
 *     <li>Response duration in milliseconds</li>
 *     <li>Request and response body (if activated in annotation)</li>
 * </ul>
 * This provider can be activated using the annotation {@link Logged} on resources. It captures bodies
 * through {@link LoggedBodyInterceptor}, which an application registering its providers explicitly must
 * register along with it.
 * <p>
 * Resolved annotation configurations are delegated to {@link #resolver}, and body filter instances to
 * {@link #bodyFilterFactory}: both cache their results per resource / filter class and never evict them.
 * This assumes a bounded, stable set of resource methods and {@link LoggedBodyFilter} classes, as is the
 * case for a typical application with a fixed set of JAX-RS endpoints; it is not suited to applications
 * that generate new resource classes at runtime (e.g. per-tenant code generation).
 * <p>
 * Declares a priority lower than the JAX-RS default ({@link Priorities#USER}) so this provider runs as
 * early as possible among request filters/interceptors and, symmetrically, as late as possible among
 * response filters/interceptors. Without it, ordering relative to other unprioritized providers is left
 * to the container, which can leave the MDC context this provider establishes (request identifier,
 * method, URI, ...) unavailable to another filter/interceptor that runs before it, or have another
 * provider observe a request/response body already altered by this one's stream wrapping (or vice versa).
 * A subclass can override this by declaring its own {@link Priority}.
 * <p>
 * That low priority is the right one for the filters but the wrong one for capturing bodies, as it places
 * this provider's interceptors outside any entity coder and therefore in front of the compressed bytes
 * rather than the entity itself. Capture is consequently delegated to {@link LoggedBodyInterceptor},
 * which runs after the coder and hands what it captures back here; see that class for the details.
 */
@Logged
@Provider
@ConstrainedTo(SERVER)
@Priority(Priorities.HEADER_DECORATOR)
public class LoggedFilter implements ContainerRequestFilter, ContainerResponseFilter, ReaderInterceptor, WriterInterceptor {

    protected static final Logger log = LoggerFactory.getLogger(LoggedFilter.class);

    /**
     * Name of the header carrying the request identifier, read here from an incoming request and
     * written by {@link LoggedClientFilter} on an outgoing one, so a client using both ends up with the
     * same identifier in MDC on both sides of the call.
     */
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    /**
     * Configuration of this provider: the names of its MDC entries, how it identifies a request, which
     * parameters it keeps out of the logs, the level it logs a request at and how it captures bodies.
     */
    protected final LoggedFilterConfiguration configuration;

    /**
     * Instantiates and caches {@link LoggedBodyFilter} instances by class.
     */
    protected final LoggedBodyFilterFactory bodyFilterFactory = new LoggedBodyFilterFactory();

    /**
     * Resolves which {@link LoggedMapping} and {@link LoggedBody} configuration applies to the resource
     * method matched by the current request, caching results per resource.
     */
    protected final LoggedResolver resolver = new LoggedResolver(bodyFilterFactory);

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
     * recording the key against the request whose entries this thread carries, so it is removed once that
     * request has been fully processed (see {@link RequestMdc}).
     * <p>
     * Every MDC entry set by this provider, or a subclass extending it, should go through this method
     * (or the {@link #putMdc(LoggedField, String)} overload) rather than {@link MDC#put(String, String)}
     * directly, to guarantee it does not outlive the request.
     *
     * @param key   The MDC key
     * @param value The value to be associated with the given key, ignored if {@code null} or blank
     */
    protected void putMdc(String key, String value) {
        mdc.put(key, value);
    }

    /**
     * Puts a diagnostic context value identified by the given field into the current thread's context map,
     * unless the field is left out (see {@link LoggedFilterConfiguration.Builder#withoutField(LoggedField)}).
     *
     * @param field The field for which put the given value
     * @param value The value to be associated with the given field
     */
    protected void putMdc(LoggedField field, String value) {
        mdc.put(field, value);
    }

    /**
     * Gets a diagnostic context value identified by the given field from the current thread's context map.
     *
     * @param field The field for which get the value
     * @return The value associated with the given field, {@code null} for a field left out (see
     * {@link LoggedFilterConfiguration.Builder#withoutField(LoggedField)})
     */
    protected String getMdc(LoggedField field) {
        return mdc.get(field);
    }

    /**
     * Removes control characters (e.g. CR, LF) from the given value.
     * <p>
     * Meant to be applied to values sourced from client-controlled input (headers, query or path
     * parameters) before storing them in MDC, to prevent log injection (an attacker forging fake log
     * entries by including line breaks in a header, query or path parameter value). Not applied to
     * logged request/response bodies, as those are expected to legitimately contain line breaks.
     *
     * @param value The value to sanitize
     * @return The sanitized value, or {@code null} if the given value was {@code null}
     */
    protected static String sanitize(String value) {
        return RequestDescriber.sanitize(value);
    }

    /**
     * Runs the given logging action, swallowing anything it throws, so that logging a request can never
     * be the reason it fails. See {@link LoggedSupport#safely(Logger, String, Runnable)}.
     *
     * @param action The logging action to run
     */
    private void safely(Runnable action) {
        LoggedSupport.safely(log, "Unable to log the request or response, the exchange itself is left unaffected", action);
    }

    /**
     * Gets the configuration a body about to be read or written is captured with, which is
     * {@link LoggedBodyConfiguration#NONE} when nothing is to be captured at all.
     * <p>
     * Guarded like the rest of the capture setup (see {@link BodyCapturer}), and for the same reason:
     * deciding whether to capture happens before {@code proceed()}, so a configuration that cannot be
     * resolved - the container's {@link ResourceInfo} failing outside of the request scope it expects, a
     * subclass overriding the resolution - would otherwise fail the read or the write of the entity itself,
     * leaving the exchange broken over a body nobody was going to log.
     *
     * @param state  The state of the request, or {@code null} if this provider never saw it start
     * @param target The direction of the body about to be read or written
     * @return The configuration to capture the body with, never {@code null}
     */
    private LoggedBodyConfiguration getCaptureConfiguration(LoggedRequestState state, Direction target) {
        // A request already completed has had its "Processed ..." line logged, so nothing captured from now
        // on - the later parts of a chunked or event stream response - could ever be logged
        if (!isLoggingEnabled() || (state != null && state.isCompleted())) {
            return LoggedBodyConfiguration.NONE;
        }
        return LoggedSupport.safely(log, BodyCapturer.CAPTURE_FAILURE, () -> getBodyConfiguration(state, target), LoggedBodyConfiguration.NONE);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Guarded the way every other callback of this provider is (see LoggedSupport#safely). This one
        // was the exception, and the only one whose failure costs more than a log line: it runs before
        // the resource method does, so a subclass overriding one of the methods below, a container
        // returning an unexpected null from the request it describes, or an appender that ran out of
        // disk did not merely lose the "Received ..." line, it answered a perfectly serviceable request
        // with an error having nothing to do with it.
        safely(() -> {
            // Attaches the state to the request, which starts measuring its duration and records this
            // instance as the one handling it, for LoggedBodyInterceptor to hand its captures back to
            LoggedRequestState state = LoggedRequestState.of(requestContext, this);
            // From here on, the entries this thread carries are this request's
            mdc.start(state);

            putMdcFromRequest(requestContext);
            putMdcFromMappings(requestContext);

            // Logs directly from filter in case no request body is expected as aroundReadFrom will not be called
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
     * is client-controlled - including the identifier the configuration gets for it.
     *
     * @param requestContext The context of the request received
     */
    private void putMdcFromRequest(ContainerRequestContext requestContext) {
        describer.describe(requestContext, resourceInfo, configuration.requestIdOf(requestContext), this::putMdc);
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
     * Indicates whether anything this provider writes would actually reach an appender.
     * <p>
     * Used to skip body capture entirely when it would be thrown away: buffering (and filtering, and
     * decoding) every request and response body of an application whose logger is configured above
     * {@code INFO} is pure overhead, and is exactly the kind of cost that is invisible until it shows
     * up as allocation pressure in production.
     * <p>
     * Checks {@code INFO}, the lowest level this provider writes at, and not the level the completion of
     * this particular request will end up being logged at (see
     * {@link LoggedFilterConfiguration.Builder#responseLevel(java.util.function.IntFunction)}): whether a
     * request failed is only known once it has been answered, long after the decision to capture its body
     * had to be made. An application configured above {@code INFO} therefore gets its failures logged at
     * {@code WARN}/{@code ERROR} but without bodies, which is the deliberate trade: the alternative is
     * buffering every body of every request in case it turns out to fail.
     *
     * @return {@code true} if the log lines written by this provider are enabled, {@code false} otherwise
     */
    private boolean isLoggingEnabled() {
        return log.isInfoEnabled();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Note that most JAX-RS implementations only invoke this interceptor when the resource method
     * actually reads the request entity (for example, when it declares an entity parameter). If a
     * request has a body but no resource method parameter consumes it, this method is never called, so
     * the request body will not be logged, even if activated in the annotation. The {@code "Received ..."}
     * line itself is still logged (without a body) by the fallback in
     * {@link #filter(ContainerRequestContext, ContainerResponseContext)}, see {@link LoggedRequestState#isRequestLogged()}.
     */
    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        try {
            return context.proceed();
        } finally {
            // Logs whatever was captured even if reading the entity failed (e.g. malformed payload),
            // so a deserialization error does not leave the request entirely unlogged
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
     * Called by {@link LoggedBodyInterceptor} rather than from this provider's own interceptor position,
     * so what is captured is the entity's own representation rather than the transfer-encoded bytes on
     * the wire (see that class for why the two positions differ).
     *
     * @param context The context of the entity being read
     * @return The entity read
     * @throws IOException              if an IO error arises while reading the entity
     * @throws WebApplicationException  if the entity cannot be read
     */
    Object captureRequestBody(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        LoggedRequestState state = LoggedRequestState.find(context);
        // The state is only read once a body was captured, which the configuration rules out without one
        return bodyCapturer.read(context, getCaptureConfiguration(state, REQUEST), body -> state.setRequestBody(body));
    }

    /**
     * Logs the request received by the server.
     * Note that the request method and URI must have been stored in MDC before calling this method.
     * <p>
     * Several callbacks can reach this for the same request, so a line repeating one already logged is
     * skipped (see {@link LoggedRequestState#markRequestLogged(boolean)}): a request whose entity was read
     * twice used to be logged twice, body included.
     *
     * @param state       The state of the request being logged
     * @param requestBody The request body to be logged
     */
    private void logRequest(LoggedRequestState state, String requestBody) {
        if (state.markRequestLogged(isNotBlank(requestBody))) {
            exchangeLogger.received(getMdc(REQUEST_METHOD), getMdc(REQUEST_URI), requestBody);
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        // Guarded the way every other callback of this provider is (see LoggedSupport#safely). The resource
        // method has already run by the time this one does, so anything escaping it - a subclass overriding
        // one of the methods below, the appender the lines are written to, a body configuration that cannot
        // be resolved - does not merely lose a log line: it answers with a 500 a request the application
        // served successfully, which a client is then free to retry, repeating whatever the request did.
        safely(() -> {
            LoggedRequestState state = startedState(requestContext);
            if (state != null) {
                mdc.onBehalfOf(state, () -> describeResponse(state, responseContext));
            }
        });
    }

    /**
     * Gets the state of the given request, first establishing it for a request whose start this provider
     * never saw.
     * <p>
     * That happens whenever a filter running earlier - an authentication one sits at
     * {@link Priorities#AUTHENTICATION}, well below this provider's priority - aborts the request, which
     * skips the rest of the request filter chain while the container still runs every response filter.
     * Every 401 or 403 an application rejects that way was consequently logged with none of the fields
     * describing the request it answers, and with whatever a previous request left behind on this (pooled)
     * thread rather than with nothing at all. Establishing the context here is the same work, only late:
     * the duration starts counting from this point, as there is nothing left to say when the request
     * actually arrived.
     *
     * @param requestContext The context of the request received
     * @return The state of the request, or {@code null} if even establishing it failed
     */
    private LoggedRequestState startedState(ContainerRequestContext requestContext) {
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
            // Fallback for a request that has a body but whose resource method never reads it: neither
            // the immediate path in filter(ContainerRequestContext) nor aroundReadFrom logged the request
            // in that case (see LoggedRequestState#isRequestLogged), so without this the "Received ..."
            // line would never appear even though LogType.LOG is configured
            if (getBodyConfiguration(state, REQUEST).logs(LOG) && !state.isRequestLogged()) {
                logRequest(state, EMPTY);
            }

            putMdc(RESPONSE_STATUS, valueOf(responseContext.getStatus()));
            exchangeLogger.returnRequestId(responseContext.getHeaders(), getMdc(REQUEST_ID));
        } finally {
            // Logs directly from filter in case no response body is present, as aroundWriteTo will not
            // be called by the container in that case (e.g. 204 No Content, HEAD requests). This must
            // happen unconditionally (not just when body logging is configured, nor only when describing
            // the response above worked), as this is also where the MDC context for the request is
            // cleaned up; skipping it here would silently drop the "Processed" log line and leak MDC
            // fields onto the thread (which is normally pooled and reused) for as long as it takes
            // another request handled by that same thread to overwrite them.
            if (!responseContext.hasEntity()) {
                logResponse(state, EMPTY);
            }
        }
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        try {
            context.proceed();
        } finally {
            // Always log and clean up MDC, even if writing the response body fails (e.g. client
            // disconnection, serialization error), to avoid leaking context fields onto a pooled thread
            // and to avoid leaving the response entirely unlogged
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
            if (bodyConfiguration.logs(LoggedBody.LogType.MDC)) {
                putMdc(RESPONSE_BODY, body);
            }
            logResponse(state, bodyConfiguration.logs(LOG) ? requireNonNullElse(body, EMPTY) : EMPTY);
        } catch (RuntimeException e) {
            // Completion is what cleans MDC up, so it must still happen when assembling the log
            // line above failed, or the fields would stay behind on this (pooled) thread
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
     * Logs the response sent by the server and completes the request/response cycle for this provider
     * (see {@link LoggedRequestState#markCompleted()}).
     * <p>
     * This is the single completion point for a request: idempotent, so it is safe to call from more
     * than one callback without risking a duplicate "Processed" line, and unconditional, so cleanup
     * always happens even when nothing about body logging applies to this request.
     * <p>
     * The duration is measured here rather than when the response filter runs, so it covers serializing
     * and writing the entity too: a response whose body takes 200ms to render used to be reported as
     * having been processed in the handful of milliseconds preceding it.
     * <p>
     * Note that the response status must have been stored in MDC before calling this method.
     *
     * @param state        The state of the request being completed
     * @param responseBody The response body to be logged
     */
    void logResponse(LoggedRequestState state, String responseBody) {
        if (!state.markCompleted()) {
            return;
        }

        try {
            putMdc(DURATION, valueOf(state.getElapsedMillis()));

            if (getBodyConfiguration(state, REQUEST).logs(LoggedBody.LogType.MDC)) {
                putMdc(REQUEST_BODY, state.getRequestBody());
            }

            exchangeLogger.processed(getMdc(REQUEST_METHOD), getMdc(REQUEST_URI), getMdc(RESPONSE_STATUS), getMdc(DURATION), responseBody);
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
    LoggedBodyConfiguration getBodyConfiguration(LoggedRequestState state, Direction target) {
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
     * for the given request (see {@link LoggedRequestState#getMdcKeys()}), which covers every entry put by
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
