package com.chavaillaz.jakarta.rs;

import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.chavaillaz.jakarta.rs.LoggedResolver.BodyConfiguration;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.ext.InterceptorContext;
import org.slf4j.MDC;

/**
 * Everything {@link LoggedFilter} remembers about one request while it is being processed.
 * <p>
 * A JAX-RS provider is a singleton shared by every concurrent request, so anything it needs to carry
 * from one callback to the next has to live on the request rather than on the provider. The container
 * offers exactly one place for that, the untyped {@code String -> Object} property map, which is why
 * this used to be eight separate property-name constants read back through casts and
 * {@code Boolean.TRUE.equals} checks. Gathering them into one object stored under one property gives
 * them a type, a name, and somewhere to state the invariants they share; it also lets the provider read
 * as the sequence of decisions it makes rather than as bookkeeping.
 * <p>
 * Every callback reaches it through the context it is handed - a filter's request context or an
 * interceptor's context, which share the request's property map - rather than through an injected
 * {@code @Context ContainerRequestContext}: the JAX-RS contract does not list that type among the ones
 * a provider can have injected, and RESTEasy indeed refuses it, failing every single request.
 * <p>
 * The request rather than the thread is deliberate throughout: a request can be started on one thread
 * and completed on another (a {@code @Suspended} response resumed from a worker, a reactive resource
 * method), so the flags are atomic and the mutable fields volatile. The state is created on the thread
 * handling the start of the request, before any hand-off can have happened.
 *
 * @see #of(ContainerRequestContext, LoggedFilter)
 */
public class LoggedRequestState {

    /**
     * Name of the single container property this state is stored under.
     * <p>
     * Qualified with the class name, as the property map is shared with the container, the application
     * and every other provider registered alongside this one.
     */
    protected static final String PROPERTY = LoggedRequestState.class.getName();

    /**
     * Provider handling this request, handed to {@link LoggedBodyInterceptor}, which captures the bodies
     * from a later position in the interceptor chain but delegates every decision about them back here.
     * <p>
     * Passed through the request rather than injected there, so the capture is handed back to the exact
     * instance - possibly one of several registered, each with its own configuration - that is handling this
     * particular request.
     */
    private final LoggedFilter provider;

    /**
     * Moment the request started, read back once it completes to report how long it took.
     */
    private final long startTime;

    /**
     * Every {@link MDC} entry put for this request through {@link LoggedFilter#putMdc(String, String)}, so a
     * thread completing the request without carrying its entries can be lent them, and so they are removed
     * exactly once the request is done (see {@link RequestMdc}).
     * <p>
     * {@code putMdc} takes no request to record the entry against, so that a subclass can call it from
     * anywhere it describes a request: this very map is bound to the thread whose MDC holds the request's
     * entries, and the entry lands here. It holds plain strings rather than anything of this library's own
     * for that reason: a value bound to a pooled thread outlives the request, and possibly the application,
     * so it must not hold on to a class the application's class loader would then never be able to unload.
     * <p>
     * Removing the entries of a request completed on a different thread than the one that put them does not
     * reach that thread, which is why the entries a request left on a thread are swept when the next request
     * starts there. Concurrent for the same reason: entries can be recorded and read from different threads.
     */
    private final Map<String, String> mdcEntries = new ConcurrentHashMap<>();

    /**
     * Whether the {@code "Received ..."} line has already been logged for this request.
     * <p>
     * That line is normally emitted either directly from {@link LoggedFilter#filter(ContainerRequestContext)}
     * (no entity expected) or from {@link LoggedFilter#aroundReadFrom(jakarta.ws.rs.ext.ReaderInterceptorContext)}
     * (entity read). However, most JAX-RS implementations only invoke the latter when the resource method
     * actually reads the request entity: a request that has a body but whose resource method declares no
     * parameter consuming it hits neither path, and without this flag the response filter could not tell
     * whether it still has to emit the line - so it would either be lost or logged twice.
     */
    private final AtomicBoolean requestLogged = new AtomicBoolean();

    /**
     * Whether the {@code "Received ..."} line has already been logged with the request body, see
     * {@link #markRequestLogged(boolean)}.
     * <p>
     * Tracked apart from {@link #requestLogged}, as the two do not repeat each other. The entity of a
     * request can be read more than once - a buffered entity read by a filter validating its signature,
     * then by the resource method - and every read goes through the interceptors again, so only a body
     * already logged makes a line carrying it a repetition. A body read after the line announcing the
     * request without one, on the other hand, is precisely what that line lacked: whether a request has a
     * body is decided before it is read, by some containers from its {@code Content-Type} header alone, so
     * a body sent without one is announced as absent before the resource method reads it after all.
     */
    private final AtomicBoolean requestBodyLogged = new AtomicBoolean();

    /**
     * Whether the request has already been completed, guarding
     * {@link LoggedFilter#logResponse(LoggedRequestState, String)} against running more than once.
     * <p>
     * Depending on whether the response has an entity, the end of a request/response cycle can be reached
     * from two different callbacks: the response filter (no entity) or the writer interceptor (entity
     * present). Rather than relying on the conditions in those two callbacks to always stay perfectly
     * mutually exclusive - which is what let the "Processed" log line and the MDC cleanup silently
     * disappear for entity-less responses in the past - completion is centralized and made idempotent:
     * whichever callback gets there first wins, and the other becomes a no-op.
     */
    private final AtomicBoolean completed = new AtomicBoolean();

    /**
     * Body logging configuration resolved for this request, kept so the several callbacks asking for it
     * share one resolution. See {@link LoggedFilter#getBodyConfiguration(LoggedRequestState, LoggedBody.Direction)}.
     */
    private volatile BodyConfiguration bodyConfiguration;

    /**
     * Request body as captured and filtered, waiting for the callback that logs it.
     */
    private volatile String requestBody;

    /**
     * Response body as captured and filtered, waiting for the callback that logs it.
     */
    private volatile String responseBody;

    /**
     * Creates the state of a request handled by the given provider, starting its duration measurement.
     *
     * @param provider The provider handling the request
     */
    protected LoggedRequestState(LoggedFilter provider) {
        this.provider = provider;
        this.startTime = nanoTime();
    }

    /**
     * Gets the state of the request carried by the given context, creating and attaching it on first use.
     *
     * @param context  The context of the request being processed
     * @param provider The provider handling the request, recorded when the state is created
     * @return The state of the request, never {@code null}
     */
    public static LoggedRequestState of(ContainerRequestContext context, LoggedFilter provider) {
        LoggedRequestState state = find(context);
        if (state == null) {
            state = new LoggedRequestState(provider);
            context.setProperty(PROPERTY, state);
        }
        return state;
    }

    /**
     * Gets the state of the request carried by the given context, without creating one.
     *
     * @param context The context of the request being processed
     * @return The state of the request, or {@code null} if no {@link LoggedFilter} is active on it
     */
    public static LoggedRequestState find(ContainerRequestContext context) {
        return asState(context.getProperty(PROPERTY));
    }

    /**
     * Gets the state of the request carried by the given context, without creating one.
     * <p>
     * The overload an interceptor uses: {@link InterceptorContext} exposes the same request-scoped
     * property map, but the JAX-RS API gives it no supertype in common with
     * {@link ContainerRequestContext} to read it through.
     *
     * @param context The context of the entity being read or written
     * @return The state of the request, or {@code null} if no {@link LoggedFilter} is active on it
     */
    public static LoggedRequestState find(InterceptorContext context) {
        return asState(context.getProperty(PROPERTY));
    }

    /**
     * Reads a container property as a state, tolerating both its absence and anything else found under
     * that name: no state simply means no {@link LoggedFilter} is active for this request (the resource
     * is not annotated, or {@link LoggedBodyInterceptor} was registered without it).
     *
     * @param property The value found in the property map
     * @return The state, or {@code null} if there is none
     */
    private static LoggedRequestState asState(Object property) {
        return property instanceof LoggedRequestState state ? state : null;
    }

    /**
     * Gets the provider handling this request, see {@link #provider}.
     *
     * @return The provider handling this request
     */
    public LoggedFilter getProvider() {
        return provider;
    }

    /**
     * Gets how long this request has been running, in milliseconds.
     * <p>
     * Read once the response has been written rather than when the response filter runs, so it covers
     * serializing and writing the entity too: a response whose body takes 200ms to render used to be
     * reported as having been processed in the handful of milliseconds preceding it.
     *
     * @return The time elapsed since the request started, in milliseconds
     */
    public long getElapsedMillis() {
        return NANOSECONDS.toMillis(nanoTime() - startTime);
    }

    /**
     * Gets the MDC entries put so far for this request, see {@link #mdcEntries}.
     *
     * @return The mutable, concurrent map of the entries to be removed from MDC once the request is done
     */
    public Map<String, String> getMdcEntries() {
        return mdcEntries;
    }

    /**
     * Records that the {@code "Received ..."} line is being logged for this request, with or without its
     * body, and tells whether that line says anything the ones logged before it did not.
     * <p>
     * A line without a body is new for a request not logged at all yet, and a line with a body for a
     * request whose body has not been logged yet, whatever was logged without it (see
     * {@link #requestBodyLogged}).
     *
     * @param withBody Whether the line carries the request body
     * @return {@code true} if the line is new and must be logged, {@code false} if it would repeat one
     */
    public boolean markRequestLogged(boolean withBody) {
        boolean first = !requestLogged.getAndSet(true);
        return withBody ? requestBodyLogged.compareAndSet(false, true) : first;
    }

    /**
     * Indicates whether the {@code "Received ..."} line has already been logged, see {@link #requestLogged}.
     *
     * @return {@code true} if the request has already been logged, {@code false} otherwise
     */
    public boolean isRequestLogged() {
        return requestLogged.get();
    }

    /**
     * Records that this request has been completed, see {@link #completed}.
     *
     * @return {@code true} if this call was the one that completed it, {@code false} if it already was
     */
    public boolean markCompleted() {
        return completed.compareAndSet(false, true);
    }

    /**
     * Indicates whether this request has been completed, see {@link #completed}.
     *
     * @return {@code true} if the request has been completed, {@code false} otherwise
     */
    public boolean isCompleted() {
        return completed.get();
    }

    /**
     * Gets the body logging configuration resolved for this request, see {@link #bodyConfiguration}.
     *
     * @return The configuration, or {@code null} if it has not been resolved yet
     */
    public BodyConfiguration getBodyConfiguration() {
        return bodyConfiguration;
    }

    /**
     * Sets the body logging configuration resolved for this request.
     *
     * @param bodyConfiguration The configuration resolved
     */
    public void setBodyConfiguration(BodyConfiguration bodyConfiguration) {
        this.bodyConfiguration = bodyConfiguration;
    }

    /**
     * Gets the captured request body, see {@link #requestBody}.
     *
     * @return The request body as it must be logged, or {@code null} if none was captured
     */
    public String getRequestBody() {
        return requestBody;
    }

    /**
     * Sets the captured request body.
     *
     * @param requestBody The request body as it must be logged
     */
    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    /**
     * Gets the captured response body, see {@link #responseBody}.
     *
     * @return The response body as it must be logged, or {@code null} if none was captured
     */
    public String getResponseBody() {
        return responseBody;
    }

    /**
     * Sets the captured response body.
     *
     * @param responseBody The response body as it must be logged
     */
    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

}
