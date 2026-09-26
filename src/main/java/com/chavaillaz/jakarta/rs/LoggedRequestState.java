package com.chavaillaz.jakarta.rs;

import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.ext.InterceptorContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import com.chavaillaz.jakarta.rs.LoggedResolver.BodyConfiguration;

/**
 * Everything {@link LoggedFilter} remembers about one request while it is being processed.
 * <p>
 * A JAX-RS provider is shared by every concurrent request, and can even be instantiated once per contract it
 * implements - RESTEasy does for one registered as a class, its request filter then being another instance
 * than its response filter - so what it carries from one callback to the next lives on the request, in its
 * property map, under a single property. Every callback reaches it through the context it is handed - a
 * filter's request context or an interceptor's context, which share that map - rather than through an
 * injected {@code @Context ContainerRequestContext}, which the JAX-RS contract does not provide for, and
 * RESTEasy refuses.
 * <p>
 * A request can start on one thread and complete on another (a {@code @Suspended} response resumed from a
 * worker, a reactive resource method), so the flags are atomic and the mutable fields volatile.
 *
 * @see #attach(ContainerRequestContext, LoggedFilter)
 */
final class LoggedRequestState {

    /**
     * Name of the single container property this state is stored under.
     * <p>
     * Qualified with the class name, as the property map is shared with the container, the application
     * and every other provider registered alongside this one.
     */
    private static final String PROPERTY = LoggedRequestState.class.getName();

    /**
     * Provider handling this request, which {@link LoggedBodyInterceptor} hands the bodies it captures back
     * to: read from the request rather than injected there, as several providers, each configured its own
     * way, can be registered. The providers of its class are the only ones logging the request.
     */
    private final LoggedFilter provider;

    /**
     * Moment the request started, read back once it completes to report how long it took.
     */
    private final long startTime;

    /**
     * Every {@link MDC} entry put for this request through {@link LoggedFilter#putMdc(String, String)}, so a
     * thread completing the request without carrying its entries can be lent them, and so they are removed
     * once the request is done (see {@link RequestMdc}).
     * <p>
     * This map is bound to the thread carrying the request, which is how {@code putMdc} records an entry
     * without being told which request it belongs to. It holds nothing but strings, as a value bound to a
     * pooled thread can outlive the application and must not pin its class loader, and is concurrent, as
     * entries can be recorded and read from different threads.
     */
    private final Map<String, String> mdcEntries = new ConcurrentHashMap<>();

    /**
     * Whether the {@code "Received ..."} line has already been logged for this request.
     * <p>
     * The line is logged by {@link LoggedFilter#filter(ContainerRequestContext)} for a request without an
     * entity, by {@link LoggedFilter#aroundReadFrom(jakarta.ws.rs.ext.ReaderInterceptorContext)} once the
     * entity is read, and otherwise - for a resource method that never reads it - by the response filter,
     * which this tells whether the line is still due.
     */
    private final AtomicBoolean requestLogged = new AtomicBoolean();

    /**
     * Whether the {@code "Received ..."} line has already been logged with the request body, see
     * {@link #markRequestLogged(boolean)}.
     * <p>
     * Tracked apart from {@link #requestLogged}: an entity read several times - by a filter validating its
     * signature, then by the resource method - goes through the interceptors each time, and only a body
     * already logged makes a line carrying it a repetition. A body read after the line announced the request
     * without one - as some containers decide from the {@code Content-Type} header alone - is still logged.
     */
    private final AtomicBoolean requestBodyLogged = new AtomicBoolean();

    /**
     * Whether the request has already been completed, guarding
     * {@link LoggedFilter#logResponse(LoggedRequestState, String)} against running more than once.
     * <p>
     * A request completes in the response filter when the response has no entity, and in the writer
     * interceptor otherwise: whichever callback gets there first completes it, and the other does nothing.
     */
    private final AtomicBoolean completed = new AtomicBoolean();

    /**
     * Method of the request as it is logged, kept for the lines logging it rather than read back from MDC,
     * which may not carry it (see {@link LoggedFilterConfiguration.Builder#withoutField(LoggedField)}), and
     * which the application may have cleared in the meantime.
     */
    private volatile @Nullable String method;

    /**
     * URI of the request as it is logged, kept for the same reason as {@link #method}.
     */
    private volatile @Nullable String uri;

    /**
     * Status the request was answered with, kept for the same reason as {@link #method}, and {@code 0} until
     * its response is described.
     */
    private volatile int status;

    /**
     * Body logging configuration resolved for this request, kept so the several callbacks asking for it
     * share one resolution. See {@link LoggedFilter#getBodyConfiguration(LoggedRequestState, LoggedBody.Direction)}.
     */
    private volatile @Nullable BodyConfiguration bodyConfiguration;

    /**
     * Request body as captured and filtered, waiting for the callback that logs it.
     */
    private volatile @Nullable String requestBody;

    /**
     * Response body as captured and filtered, waiting for the callback that logs it.
     */
    private volatile @Nullable String responseBody;

    /**
     * Handing over of the request body the resource method reads as a stream, which sets
     * {@link #requestBody} once the stream ends, or once this runs it, whichever comes first.
     */
    private volatile @Nullable Runnable streamedRequestBody;

    /**
     * Creates the state of a request handled by the given provider, starting its duration measurement.
     *
     * @param provider The provider handling the request
     */
    LoggedRequestState(LoggedFilter provider) {
        this.provider = provider;
        this.startTime = nanoTime();
    }

    /**
     * Creates the state of a request starting, and attaches it to the request.
     *
     * @param context  The context of the request starting
     * @param provider The provider handling the request
     * @return The state of the request
     */
    static LoggedRequestState attach(ContainerRequestContext context, LoggedFilter provider) {
        LoggedRequestState state = new LoggedRequestState(provider);
        context.setProperty(PROPERTY, state);
        return state;
    }

    /**
     * Gets the state of the request carried by the given context, without creating one.
     *
     * @param context The context of the request being processed
     * @return The state of the request, or {@code null} if no {@link LoggedFilter} is active on it
     */
    static @Nullable LoggedRequestState find(ContainerRequestContext context) {
        return asState(context.getProperty(PROPERTY));
    }

    /**
     * Gets the state of the request carried by the given context, without creating one.
     * <p>
     * The overload an interceptor uses: {@link InterceptorContext} shares the property map of the request,
     * but has no supertype in common with {@link ContainerRequestContext} to read it through.
     *
     * @param context The context of the entity being read or written
     * @return The state of the request, or {@code null} if no {@link LoggedFilter} is active on it
     */
    static @Nullable LoggedRequestState find(InterceptorContext context) {
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
    private static @Nullable LoggedRequestState asState(@Nullable Object property) {
        return property instanceof LoggedRequestState state ? state : null;
    }

    /**
     * Gets the provider handling this request, see {@link #provider}.
     *
     * @return The provider handling this request
     */
    LoggedFilter getProvider() {
        return provider;
    }

    /**
     * Gets how long this request has been running, in milliseconds.
     *
     * @return The time elapsed since the request started, in milliseconds
     */
    long getElapsedMillis() {
        return NANOSECONDS.toMillis(nanoTime() - startTime);
    }

    /**
     * Gets the MDC entries put so far for this request, see {@link #mdcEntries}.
     *
     * @return The mutable, concurrent map of the entries to be removed from MDC once the request is done
     */
    Map<String, String> getMdcEntries() {
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
    boolean markRequestLogged(boolean withBody) {
        boolean first = !requestLogged.getAndSet(true);
        return withBody ? requestBodyLogged.compareAndSet(false, true) : first;
    }

    /**
     * Indicates whether the {@code "Received ..."} line has already been logged, see {@link #requestLogged}.
     *
     * @return {@code true} if the request has already been logged, {@code false} otherwise
     */
    boolean isRequestLogged() {
        return requestLogged.get();
    }

    /**
     * Records that this request has been completed, see {@link #completed}.
     *
     * @return {@code true} if this call was the one that completed it, {@code false} if it already was
     */
    boolean markCompleted() {
        return completed.compareAndSet(false, true);
    }

    /**
     * Indicates whether this request has been completed, see {@link #completed}.
     *
     * @return {@code true} if the request has been completed, {@code false} otherwise
     */
    boolean isCompleted() {
        return completed.get();
    }

    /**
     * Keeps the value of the given field describing this request, if the lines logging it read that field.
     *
     * @param field The field describing the request
     * @param value The value of the field, as it is logged
     */
    void describe(LoggedField field, @Nullable String value) {
        switch (field) {
            case REQUEST_METHOD -> method = value;
            case REQUEST_URI -> uri = value;
            default -> {
                // Not read by the lines logging the request
            }
        }
    }

    /**
     * Gets the method of this request, see {@link #method}.
     *
     * @return The method of the request as it is logged, or {@code null} if it is unknown
     */
    @Nullable String getMethod() {
        return method;
    }

    /**
     * Gets the URI of this request, see {@link #uri}.
     *
     * @return The URI of the request as it is logged, or {@code null} if it is unknown
     */
    @Nullable String getUri() {
        return uri;
    }

    /**
     * Gets the status this request was answered with, see {@link #status}.
     *
     * @return The status of the response, or {@code 0} if it has not been described yet
     */
    int getStatus() {
        return status;
    }

    /**
     * Sets the status this request was answered with.
     *
     * @param status The status of the response
     */
    void setStatus(int status) {
        this.status = status;
    }

    /**
     * Gets the body logging configuration resolved for this request, see {@link #bodyConfiguration}.
     *
     * @return The configuration, or {@code null} if it has not been resolved yet
     */
    @Nullable BodyConfiguration getBodyConfiguration() {
        return bodyConfiguration;
    }

    /**
     * Sets the body logging configuration resolved for this request.
     *
     * @param bodyConfiguration The configuration resolved
     */
    void setBodyConfiguration(BodyConfiguration bodyConfiguration) {
        this.bodyConfiguration = bodyConfiguration;
    }

    /**
     * Gets the captured request body, see {@link #requestBody}.
     *
     * @return The request body as it must be logged, or {@code null} if none was captured
     */
    @Nullable String getRequestBody() {
        return requestBody;
    }

    /**
     * Sets the captured request body.
     *
     * @param requestBody The request body as it must be logged, {@code null} if none was captured
     */
    void setRequestBody(@Nullable String requestBody) {
        this.requestBody = requestBody;
    }

    /**
     * Gets the captured response body, see {@link #responseBody}.
     *
     * @return The response body as it must be logged, or {@code null} if none was captured
     */
    @Nullable String getResponseBody() {
        return responseBody;
    }

    /**
     * Sets the captured response body.
     *
     * @param responseBody The response body as it must be logged, {@code null} if none was captured
     */
    void setResponseBody(@Nullable String responseBody) {
        this.responseBody = responseBody;
    }

    /**
     * Records the handing over of the request body the resource method reads as a stream, see
     * {@link #streamedRequestBody}.
     *
     * @param handOver The handing over of the request body, which runs once, whatever runs it first
     */
    void setStreamedRequestBody(Runnable handOver) {
        this.streamedRequestBody = handOver;
    }

    /**
     * Has the request body the resource method reads as a stream handed over, as far as it read it, if the
     * stream has not ended already: its request is being answered, and the lines logging it are due.
     */
    void endStreamedRequestBody() {
        Runnable handOver = streamedRequestBody;
        if (handOver != null) {
            handOver.run();
        }
    }

    /**
     * Releases the bodies captured for this request, logged once it completes: this state lives as long as
     * the request does, which a response written in parts - an event stream, a chunked output - keeps open
     * long after its {@code "Processed ..."} line.
     */
    void releaseBodies() {
        requestBody = null;
        responseBody = null;
        streamedRequestBody = null;
    }

}
