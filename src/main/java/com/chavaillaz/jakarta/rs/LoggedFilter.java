package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedField.DURATION;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_PARAMETERS;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_CLASS;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_STATUS;
import static com.chavaillaz.jakarta.rs.LoggedField.getDefaultFields;
import static jakarta.ws.rs.RuntimeType.SERVER;
import static java.lang.String.join;
import static java.lang.String.valueOf;
import static java.lang.System.nanoTime;
import static java.util.Comparator.comparing;
import static java.util.Map.Entry.comparingByKey;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.of;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.chavaillaz.jakarta.rs.LoggedBody.Direction;
import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
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
import org.apache.commons.io.input.TeeInputStream;
import org.apache.commons.io.output.TeeOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Provider adding the following request information to {@link MDC}:
 * <ul>
 *     <li>Request identifier (see {@link java.util.UUID})</li>
 *     <li>Request method (see {@link jakarta.ws.rs.HttpMethod})</li>
 *     <li>Request URI path relative to the base URI</li>
 *     <li>Resource class matched by the current request</li>
 *     <li>Resource method matched by the current request</li>
 * </ul>
 * Once the response computed, the request will be logged using the format
 * <code>Processed [method] [URI] with status [status] in [duration]ms</code>
 * with the following {@link MDC}:
 * <ul>
 *     <li>Response status (see {@link jakarta.ws.rs.core.Response.Status})</li>
 *     <li>Response duration in milliseconds</li>
 *     <li>Request and response body (if activated in annotation)</li>
 * </ul>
 * This provider can be activated using the annotation {@link Logged} on resources.
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
     * Pattern matching control characters (e.g. CR, LF) that must be removed from client-controlled
     * input (headers, query or path parameters) before it is stored in MDC, to prevent an attacker
     * from forging fake log entries or corrupting the log line (log injection).
     * <p>
     * Includes the Unicode line separators a plain {@code \p{Cntrl}} (ASCII-only) misses, as several
     * log viewers and JavaScript-based log pipelines treat {@code U+2028}/{@code U+2029} as line breaks.
     */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

    /**
     * Name of the property stored in container context to compute the duration time.
     */
    protected static final String REQUEST_TIME_PROPERTY = "request-time";

    /**
     * Name of the property stored in container context to retrieve the request body after its processing.
     * <p>
     * Intentionally distinct from {@link LoggedField#REQUEST_BODY}'s default MDC field name
     * ({@code request-body}), as the two serve different purposes: this is an internal request-scoped
     * context property, while the other is a user-facing, renamable MDC key.
     */
    protected static final String REQUEST_BODY_PROPERTY = LoggedFilter.class.getName() + ".requestBody";

    /**
     * Name of the property stored in container context to keep track of every {@link MDC.MDCCloseable}
     * obtained through {@link #putMdc(String, String)} for the current request, so {@link #cleanupMdc()}
     * can close exactly what was added.
     * <p>
     * This is what gives MDC entries set by this provider a single, structurally-enforced lifecycle:
     * any code, including a subclass, that wants an MDC entry removed at the end of the request must
     * go through {@link #putMdc(String, String)} (or one of its overloads) instead of calling
     * {@link MDC#put(String, String)} directly, or it will not be tracked here and will leak onto the
     * thread (normally pooled and reused) handling the next, unrelated request.
     * <p>
     * Using {@link MDC#putCloseable(String, String)} rather than hand-rolled key tracking removes an
     * entire class of bugs where an entry is put without being recorded for cleanup (or recorded under
     * the wrong key): the only way to obtain a value to put in this list is the very call that already
     * knows how to remove it, so there is nothing left to keep in sync by convention. This does not by
     * itself solve MDC being thread-local: if a request is completed (see {@link #COMPLETED_PROPERTY})
     * on a different thread than the one that called {@link #putMdc(String, String)} (for example, a
     * JAX-RS implementation that resumes a {@code @Suspended} response, or a reactive resource method,
     * on a different worker thread), closing these closeables removes the entries from the completing
     * thread's MDC, not from the thread that actually set them - which then still leaks until that
     * thread happens to process another request overwriting the same keys.
     */
    protected static final String MDC_CLOSEABLES_PROPERTY = LoggedFilter.class.getName() + ".mdcCloseables";

    /**
     * Name of the property stored in container context to guard {@link #logResponse(String)} against
     * running more than once for the same request.
     * <p>
     * Depending on whether the response has an entity, the end of a request/response cycle can be
     * reached from two different callbacks of this provider: {@link #filter(ContainerRequestContext, ContainerResponseContext)}
     * (no entity) or {@link #aroundWriteTo(WriterInterceptorContext)} (entity present). Rather than
     * relying on the conditions in those two callbacks to always stay perfectly mutually exclusive
     * (which is what let the "Processed" log line and MDC cleanup silently disappear for entity-less
     * responses in the past), completion is centralized in {@link #logResponse(String)} and made
     * idempotent: whichever callback gets there first wins, and the other becomes a no-op.
     */
    protected static final String COMPLETED_PROPERTY = LoggedFilter.class.getName() + ".completed";

    /**
     * Name of the property stored in container context to record that {@link #logRequest(String)} has
     * already emitted the "Received ..." line for the current request, so
     * {@link #filter(ContainerRequestContext, ContainerResponseContext)} knows whether it must still do so.
     * <p>
     * Most of the time, that line is emitted either directly from {@link #filter(ContainerRequestContext)}
     * (no entity expected) or from {@link #aroundReadFrom(ReaderInterceptorContext)} (entity read). However,
     * {@link #aroundReadFrom(ReaderInterceptorContext)} is only invoked by most JAX-RS implementations when
     * the resource method actually reads the request entity (see its Javadoc): a request that has a body
     * but whose resource method declares no parameter consuming it hits neither path, and without this
     * fallback the "Received ..." line - not just its body - would silently never be logged for such a
     * request, even though {@link com.chavaillaz.jakarta.rs.LoggedBody.LogType#LOG} is configured.
     */
    protected static final String REQUEST_LOGGED_PROPERTY = LoggedFilter.class.getName() + ".requestLogged";

    /**
     * Names of MDC fields to be used for all logged fields.
     * Allows changes from children classes.
     */
    protected final Map<String, String> mdcFields = getDefaultFields();

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
     * Provides request specific information for the filter.
     */
    @Context
    protected ContainerRequestContext requestContext;

    /**
     * Gets the mutable, request-scoped list of {@link MDC.MDCCloseable} obtained so far for the current
     * request through {@link #putMdc(String, String)}, creating and registering it as a container
     * property on first use.
     *
     * @return The closeables to be closed by {@link #cleanupMdc()} once the request is done
     */
    @SuppressWarnings("unchecked")
    protected List<MDC.MDCCloseable> getMdcCloseables() {
        List<MDC.MDCCloseable> closeables = (List<MDC.MDCCloseable>) requestContext.getProperty(MDC_CLOSEABLES_PROPERTY);
        if (closeables == null) {
            closeables = new ArrayList<>();
            requestContext.setProperty(MDC_CLOSEABLES_PROPERTY, closeables);
        }
        return closeables;
    }

    /**
     * Puts a diagnostic context value identified by the given key into the current thread's context map,
     * tracking the resulting {@link MDC.MDCCloseable} (see {@link #MDC_CLOSEABLES_PROPERTY}) so
     * {@link #cleanupMdc()} removes it once the request has been fully processed.
     * <p>
     * Every MDC entry set by this provider, or a subclass extending it, should go through this method
     * (or the {@link #putMdc(LoggedField, String)} overload) rather than {@link MDC#put(String, String)}
     * directly, to guarantee it does not outlive the request.
     *
     * @param key   The MDC key
     * @param value The value to be associated with the given key, ignored if {@code null}
     */
    protected void putMdc(String key, String value) {
        if (value != null) {
            getMdcCloseables().add(MDC.putCloseable(key, value));
        }
    }

    /**
     * Puts a diagnostic context value identified by the given field into the current thread's context map.
     *
     * @param field The field for which put the given value
     * @param value The value to be associated with the given field
     */
    protected void putMdc(LoggedField field, String value) {
        putMdc(mdcFields.get(field.name()), value);
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
        return value == null ? null : CONTROL_CHARACTERS.matcher(value).replaceAll(" ");
    }

    /**
     * Maps the given parameters (path, query or headers) to MDC entries using the given mapping.
     *
     * @param parameters The parameters to be mapped
     * @param mapping    The mapping to be applied
     * @param exclusion  The parameters name to be excluded from mapping (already mapped or explicitly excluded)
     */
    protected void putMdcFromParameters(Map<String, List<String>> parameters, LoggedMapping mapping, Set<String> exclusion) {
        Set<String> paramNames = Set.of(mapping.paramNames());
        if (mapping.auto()) {
            parameters.entrySet().stream()
                    .filter(entry -> !exclusion.contains(entry.getKey()))
                    .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                    .forEach(entry -> {
                        // Client-controlled parameter/header names must not be allowed to overwrite reserved MDC fields
                        String mdcKey = mapping.mdcPrefix() + sanitize(entry.getKey());
                        if (!mdcFields.containsValue(mdcKey)) {
                            putMdc(mdcKey, sanitize(entry.getValue().getFirst()));
                        }
                    });
        } else if (paramNames.stream().noneMatch(exclusion::contains)) {
            // Avoid a field to be mapped multiple times
            exclusion.addAll(paramNames);
            // MDC key can be blank in case of exclusion
            if (isNotBlank(mapping.mdcKey())) {
                paramNames.stream()
                        .map(parameters::get)
                        .filter(Objects::nonNull)
                        .filter(list -> !list.isEmpty())
                        .map(List::getFirst)
                        .findFirst()
                        .ifPresent(value -> putMdc(mapping.mdcPrefix() + mapping.mdcKey(), sanitize(value)));
            }
        }
    }

    /**
     * Gets the merged {@link LoggedMapping} definitions applicable to the resource method matched by
     * the current request, delegating resolution and caching to {@link #resolver}.
     *
     * @return The set of merged mappings applicable to the current request
     */
    protected Set<LoggedMapping> getCachedMergedMappings() {
        return resolver.getMergedMappings(resourceInfo);
    }

    /**
     * Gets a diagnostic context value identified by the given field from the current thread's context map.
     *
     * @param field The field for which get the value
     * @return The value associated with the given field
     */
    protected String getMdc(LoggedField field) {
        return MDC.get(mdcFields.get(field.name()));
    }

    /**
     * Maximum length kept from a client-supplied {@code X-Request-ID} header before it is stored in MDC.
     * <p>
     * A container's overall header size limit is shared across every header of the request, not applied
     * individually, so without a limit of its own a client can inflate every single log line written
     * during the request by supplying an excessively long identifier.
     */
    protected static final int REQUEST_ID_MAX_LENGTH = 128;

    /**
     * Gets the request identifier that will be stored in MDC for the complete request processing.
     * Returns the header value of {@code X-Request-ID} (truncated to {@link #REQUEST_ID_MAX_LENGTH}
     * characters) or a random UUID when not present.
     *
     * @param requestContext The context of the request received
     * @return The request identifier
     */
    protected String getRequestId(ContainerRequestContext requestContext) {
        return of(requestContext)
                .map(ContainerRequestContext::getHeaders)
                .map(headers -> headers.getFirst(REQUEST_ID_HEADER))
                .map(value -> value.length() > REQUEST_ID_MAX_LENGTH ? value.substring(0, REQUEST_ID_MAX_LENGTH) : value)
                // orElseGet (not orElse) so a UUID, which is comparatively expensive to generate
                // (backed by SecureRandom), is only computed when the header is actually absent
                .orElseGet(() -> randomUUID().toString());
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        requestContext.setProperty(REQUEST_TIME_PROPERTY, nanoTime());
        putMdc(REQUEST_ID, sanitize(getRequestId(requestContext)));
        putMdc(REQUEST_URI, sanitize(requestContext.getUriInfo().getPath()));
        putMdc(REQUEST_PARAMETERS, sanitize(getQueryParameters(requestContext)));
        putMdc(REQUEST_METHOD, sanitize(requestContext.getMethod()));
        Optional.ofNullable(resourceInfo.getResourceClass())
                .map(Class::getSimpleName)
                .ifPresent(value -> putMdc(RESOURCE_CLASS, value));
        Optional.ofNullable(resourceInfo.getResourceMethod())
                .map(Method::getName)
                .ifPresent(value -> putMdc(RESOURCE_METHOD, value));

        Set<LoggedMapping> mappings = getCachedMergedMappings();
        if (!mappings.isEmpty()) {
            Map<MappingType, Set<String>> exclusion = new EnumMap<>(MappingType.class);
            mappings.stream()
                    .sorted(comparing(LoggedMapping::auto) // Order to have auto mappings at the end to avoid overriding manual mappings
                            .thenComparing(LoggedMapping::mdcKey)) // Order to have empty MDC key at the beginning for exclusions
                    .forEach(mapping ->
                            putMdcFromParameters(switch (mapping.type()) {
                                case PATH -> requestContext.getUriInfo().getPathParameters();
                                case QUERY -> requestContext.getUriInfo().getQueryParameters();
                                case HEADER -> requestContext.getHeaders();
                            }, mapping, exclusion.computeIfAbsent(mapping.type(), type -> new HashSet<>())));
        }

        // Logs directly from filter in case no request body is expected as aroundReadFrom will not be called
        if (getBodyConfiguration(REQUEST).logs(LOG) && !(requestContext.hasEntity() && requestContext.getLength() != 0)) {
            logRequest(EMPTY);
        }
    }

    /**
     * Renders the query parameters of the given request as a single, deterministically ordered string.
     *
     * @param requestContext The context of the request received
     * @return The rendered query parameters, empty if the request has none
     */
    protected String getQueryParameters(ContainerRequestContext requestContext) {
        Map<String, List<String>> parameters = requestContext.getUriInfo().getQueryParameters();
        if (parameters.isEmpty()) {
            // Short-circuits the sorted stream below for the (very common) case of a request without
            // any query parameter, this method being on the hot path of every single request
            return EMPTY;
        }
        return parameters.entrySet()
                .stream()
                .sorted(comparingByKey())
                .map(entry -> entry.getKey() + "=" + join(",", entry.getValue()))
                .collect(joining("&"));
    }

    /**
     * Indicates whether anything this provider writes would actually reach an appender.
     * <p>
     * Used to skip body capture entirely when it would be thrown away: buffering (and filtering, and
     * decoding) every request and response body of an application whose logger is configured above
     * {@code INFO} is pure overhead, and is exactly the kind of cost that is invisible until it shows
     * up as allocation pressure in production.
     *
     * @return {@code true} if the log lines written by this provider are enabled, {@code false} otherwise
     */
    protected boolean isLoggingEnabled() {
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
     * {@link #filter(ContainerRequestContext, ContainerResponseContext)}, see {@link #REQUEST_LOGGED_PROPERTY}.
     */
    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        LoggedBodyConfiguration configuration = getBodyConfiguration(REQUEST);
        if (!configuration.isActive() || !isLoggingEnabled()) {
            return context.proceed();
        }

        LoggedBodyCapture capture = createBodyCapture(configuration.limit());
        context.setInputStream(new TeeInputStream(context.getInputStream(), capture.sink()));
        try {
            return context.proceed();
        } finally {
            // Logs whatever was captured even if reading the entity failed (e.g. malformed payload),
            // so a deserialization error does not leave the request entirely unlogged
            String body = capture.content(configuration.filters(), context.getMediaType());
            if (configuration.logs(LOG) && isNotBlank(body)) {
                logRequest(body);
            }
            if (configuration.logs(LoggedBody.LogType.MDC)) {
                requestContext.setProperty(REQUEST_BODY_PROPERTY, body);
            }
        }
    }

    /**
     * Creates the {@link LoggedBodyCapture} used to capture a request or response body.
     * <p>
     * This is the extension point for the mechanics of body capture itself (as opposed to
     * {@link LoggedBodyFilter}, which only transforms content already captured): override to plug in a
     * different strategy, for example spilling very large bodies to a temporary file instead of memory.
     *
     * @param limit The maximum size of the body to capture in bytes, or {@code -1} for no limit
     * @return The body capture to use
     */
    protected LoggedBodyCapture createBodyCapture(int limit) {
        return new BoundedLoggedBodyCapture(limit);
    }

    /**
     * Logs the request received by the server.
     * Note that the request method and URI must have been stored in MDC before calling this method.
     *
     * @param requestBody The request body to be logged
     */
    protected void logRequest(String requestBody) {
        requestContext.setProperty(REQUEST_LOGGED_PROPERTY, Boolean.TRUE);
        log.info("Received {} {}{}{}",
                getMdc(REQUEST_METHOD),
                getMdc(REQUEST_URI),
                isNotBlank(requestBody) ? LF : EMPTY,
                requestBody);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        // Fallback for a request that has a body but whose resource method never reads it: neither the
        // immediate path in filter(ContainerRequestContext) nor aroundReadFrom logged the request in that
        // case (see REQUEST_LOGGED_PROPERTY), so without this the "Received ..." line would silently never
        // appear even though LogType.LOG is configured
        if (getBodyConfiguration(REQUEST).logs(LOG) && !Boolean.TRUE.equals(this.requestContext.getProperty(REQUEST_LOGGED_PROPERTY))) {
            logRequest(EMPTY);
        }

        long requestStartTime = Optional.ofNullable(requestContext.getProperty(REQUEST_TIME_PROPERTY))
                .map(Number.class::cast)
                .map(Number::longValue)
                .orElseGet(System::nanoTime);
        long duration = (nanoTime() - requestStartTime) / 1_000_000;
        putMdc(DURATION, valueOf(duration));
        putMdc(RESPONSE_STATUS, valueOf(responseContext.getStatus()));

        // Logs directly from filter in case no response body is present, as aroundWriteTo will not be
        // called by the container in that case (e.g. 204 No Content, HEAD requests). This must happen
        // unconditionally (not just when body logging is configured), as this is also where the MDC
        // context for the request is cleaned up; skipping it here would silently drop the "Processed"
        // log line and leak MDC fields onto the thread (which is normally pooled and reused) for as
        // long as it takes another request handled by that same thread to overwrite them.
        if (!responseContext.hasEntity()) {
            logResponse(EMPTY);
        }
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        String responseBody = null;
        LoggedBodyConfiguration configuration = getBodyConfiguration(RESPONSE);
        try {
            if (!configuration.isActive() || !isLoggingEnabled()) {
                context.proceed();
                return;
            }

            LoggedBodyCapture capture = createBodyCapture(configuration.limit());
            context.setOutputStream(new TeeOutputStream(context.getOutputStream(), capture.sink()));
            try {
                context.proceed();
            } finally {
                // Logs/stores whatever was captured even if writing the entity failed (e.g. client
                // disconnection, serialization error), so such a failure does not leave the response
                // entirely unlogged, mirroring aroundReadFrom's handling of the request body
                String body = capture.content(configuration.filters(), context.getMediaType());
                if (configuration.logs(LoggedBody.LogType.MDC)) {
                    putMdc(RESPONSE_BODY, body);
                }
                if (configuration.logs(LOG)) {
                    responseBody = body;
                }
            }
        } finally {
            // Always log and clean up MDC, even if writing the response body fails
            // (e.g. client disconnection), to avoid leaking context fields onto a pooled thread
            logResponse(requireNonNullElse(responseBody, EMPTY));
        }
    }

    /**
     * Logs the response sent by the server and completes the request/response cycle for this provider
     * (see {@link #COMPLETED_PROPERTY}).
     * <p>
     * This is the single completion point for a request: idempotent, so it is safe to call from more
     * than one callback without risking a duplicate "Processed" line, and unconditional, so cleanup
     * always happens even when nothing about body logging applies to this request.
     * <p>
     * Note that the response status and duration must have been stored in MDC before calling this method.
     *
     * @param responseBody The response body to be logged
     */
    protected void logResponse(String responseBody) {
        if (Boolean.TRUE.equals(requestContext.getProperty(COMPLETED_PROPERTY))) {
            return;
        }
        requestContext.setProperty(COMPLETED_PROPERTY, Boolean.TRUE);

        try {
            if (getBodyConfiguration(REQUEST).logs(LoggedBody.LogType.MDC)) {
                putMdc(REQUEST_BODY, (String) requestContext.getProperty(REQUEST_BODY_PROPERTY));
            }

            log.info("Processed {} {} with status {} in {}ms{}{}",
                    getMdc(REQUEST_METHOD),
                    getMdc(REQUEST_URI),
                    getMdc(RESPONSE_STATUS),
                    getMdc(DURATION),
                    isNotBlank(responseBody) ? LF : EMPTY,
                    responseBody);

        } finally {
            cleanupMdc();
        }
    }

    /**
     * Gets the body logging configuration for the given target (request or response) of the resource
     * method matched by the current request, delegating resolution and caching to {@link #resolver}.
     *
     * @param target The target for which to find the body logging configuration
     * @return The body logging configuration, never {@code null}
     */
    protected LoggedBodyConfiguration getBodyConfiguration(Direction target) {
        return resolver.getBodyConfiguration(resourceInfo, target);
    }

    /**
     * Closes every {@link MDC.MDCCloseable} obtained through {@link #putMdc(String, String)} for the
     * current request (see {@link #MDC_CLOSEABLES_PROPERTY}), which covers every entry put by this
     * provider itself as well as any subclass following the same convention.
     * <p>
     * Also sweeps the fixed set of fields in {@link #mdcFields} as a safety net, in case a subclass
     * still puts one of those directly through {@link MDC#put(String, String)} (as opposed to
     * {@link #putMdc(LoggedField, String)}) after registering it there.
     */
    protected void cleanupMdc() {
        mdcFields.values().forEach(MDC::remove);
        getMdcCloseables().forEach(MDC.MDCCloseable::close);
    }

}
