package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
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
import static com.chavaillaz.jakarta.rs.LoggedUtils.getAnnotation;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getMergedMappings;
import static jakarta.ws.rs.RuntimeType.SERVER;
import static java.lang.String.join;
import static java.lang.String.valueOf;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.Map.Entry.comparingByKey;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.of;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.LF;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.chavaillaz.jakarta.rs.LoggedBody.LogType;
import com.chavaillaz.jakarta.rs.LoggedBody.Direction;
import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import jakarta.ws.rs.ConstrainedTo;
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
 * Resolved annotation configurations and body filter instances are cached per resource method / filter
 * class ({@code filtersCache}, {@code bodyConfigurationCache}, {@code mappingsCache}) and never evicted.
 * This assumes a bounded, stable set of resource methods and {@link LoggedBodyFilter} classes, as is the
 * case for a typical application with a fixed set of JAX-RS endpoints; it is not suited to applications
 * that generate new resource classes at runtime (e.g. per-tenant code generation).
 */
@Logged
@Provider
@ConstrainedTo(SERVER)
public class LoggedFilter implements ContainerRequestFilter, ContainerResponseFilter, ReaderInterceptor, WriterInterceptor {

    protected static final Logger log = LoggerFactory.getLogger(LoggedFilter.class);

    /**
     * Pattern matching control characters (e.g. CR, LF) that must be removed from client-controlled
     * input (headers, query or path parameters) before it is stored in MDC, to prevent an attacker
     * from forging fake log entries or corrupting the log line (log injection).
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
     * Name of the property stored in container context to keep track of every MDC key put through
     * {@link #putMdc(String, String)} for the current request, so {@link #cleanupMdc()} can remove
     * exactly what was added.
     * <p>
     * This is what gives MDC entries set by this provider a single, structurally-enforced lifecycle:
     * any code, including a subclass, that wants an MDC entry removed at the end of the request must
     * go through {@link #putMdc(String, String)} (or one of its overloads) instead of calling
     * {@link MDC#put(String, String)} directly, or it will not be tracked here and will leak onto the
     * thread (normally pooled and reused) handling the next, unrelated request.
     */
    protected static final String MDC_KEYS_PROPERTY = LoggedFilter.class.getName() + ".mdcKeys";

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
     * Names of MDC fields to be used for all logged fields.
     * Allows changes from children classes.
     */
    protected final Map<String, String> mdcFields = getDefaultFields();

    /**
     * Cache of instances for request and response body filters.
     * Uses a concurrent map as this provider is a singleton shared across concurrently processed requests.
     */
    protected final Map<Class<?>, LoggedBodyFilter> filtersCache = new ConcurrentHashMap<>();

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
     * Gets the mutable, request-scoped set of MDC keys put so far for the current request through
     * {@link #putMdc(String, String)}, creating and registering it as a container property on first use.
     *
     * @return The set of MDC keys to be removed by {@link #cleanupMdc()} once the request is done
     */
    @SuppressWarnings("unchecked")
    protected Set<String> getMdcKeys() {
        Set<String> keys = (Set<String>) requestContext.getProperty(MDC_KEYS_PROPERTY);
        if (keys == null) {
            keys = new HashSet<>();
            requestContext.setProperty(MDC_KEYS_PROPERTY, keys);
        }
        return keys;
    }

    /**
     * Puts a diagnostic context value identified by the given key into the current thread's context map,
     * tracking it (see {@link #MDC_KEYS_PROPERTY}) so {@link #cleanupMdc()} removes it once the request
     * has been fully processed.
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
            MDC.put(key, value);
            getMdcKeys().add(key);
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
                        if (!mdcFields.values().contains(mdcKey)) {
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
     * Cache of the merged {@link LoggedMapping} definitions resolved for each resource method, as it
     * only depends on the (immutable) annotations present on the matched class/method/interfaces and
     * not on request data, avoiding a reflection-based annotation lookup on every single request to
     * the same resource method.
     */
    protected final Map<Method, Set<LoggedMapping>> mappingsCache = new ConcurrentHashMap<>();

    /**
     * Gets the merged {@link LoggedMapping} definitions applicable to the resource method matched by
     * the current request, resolving and caching them once per resource method.
     *
     * @return The set of merged mappings applicable to the current request
     */
    protected Set<LoggedMapping> getCachedMergedMappings() {
        return mappingsCache.computeIfAbsent(resourceInfo.getResourceMethod(), method -> getMergedMappings(resourceInfo));
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
     * Gets the request identifier that will be stored in MDC for the complete request processing.
     * Returns the header value of {@code X-Request-ID} or a random UUID when not present.
     *
     * @param requestContext The context of the request received
     * @return The request identifier
     */
    protected String getRequestId(ContainerRequestContext requestContext) {
        return of(requestContext)
                .map(ContainerRequestContext::getHeaders)
                .map(headers -> headers.getFirst("X-Request-ID"))
                // orElseGet (not orElse) so a UUID, which is comparatively expensive to generate
                // (backed by SecureRandom), is only computed when the header is actually absent
                .orElseGet(() -> randomUUID().toString());
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        requestContext.setProperty(REQUEST_TIME_PROPERTY, nanoTime());
        putMdc(REQUEST_ID, sanitize(getRequestId(requestContext)));
        putMdc(REQUEST_URI, sanitize(requestContext.getUriInfo().getPath()));
        putMdc(REQUEST_PARAMETERS, sanitize(requestContext.getUriInfo()
                .getQueryParameters()
                .entrySet()
                .stream()
                .sorted(comparingByKey())
                .map(entry -> entry.getKey() + "=" + join(",", entry.getValue()))
                .collect(joining("&"))));
        putMdc(REQUEST_METHOD, sanitize(requestContext.getMethod()));
        Optional.ofNullable(resourceInfo.getResourceClass())
                .map(Class::getSimpleName)
                .ifPresent(value -> putMdc(RESOURCE_CLASS, value));
        Optional.ofNullable(resourceInfo.getResourceMethod())
                .map(Method::getName)
                .ifPresent(value -> putMdc(RESOURCE_METHOD, value));

        Map<MappingType, Set<String>> exclusion = new EnumMap<>(MappingType.class);
        getCachedMergedMappings().stream()
                .sorted(comparing(LoggedMapping::auto) // Order to have auto mappings at the end to avoid overriding manual mappings
                        .thenComparing(LoggedMapping::mdcKey)) // Order to have empty MDC key at the beginning for exclusions
                .forEach(mapping ->
                        putMdcFromParameters(switch (mapping.type()) {
                            case PATH -> requestContext.getUriInfo().getPathParameters();
                            case QUERY -> requestContext.getUriInfo().getQueryParameters();
                            case HEADER -> requestContext.getHeaders();
                        }, mapping, exclusion.computeIfAbsent(mapping.type(), type -> new HashSet<>())));

        // Logs directly from filter in case no request body is expected as aroundReadFrom will not be called
        if (getBodyLoggingRequest().contains(LogType.LOG) && !(requestContext.hasEntity() && requestContext.getLength() != 0)) {
            logRequest(EMPTY);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Note that most JAX-RS implementations only invoke this interceptor when the resource method
     * actually reads the request entity (for example, when it declares an entity parameter). If a
     * request has a body but no resource method parameter consumes it, this method is never called,
     * so the request body will not be logged, even if activated in the annotation.
     */
    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        Object entity;
        if (!getBodyLoggingRequest().isEmpty()) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            TeeInputStream teeInputStream = new TeeInputStream(
                    context.getInputStream(),
                    new BoundedOutputStream(outputStream, getBodyLimitRequest()));
            context.setInputStream(teeInputStream);
            try {
                entity = context.proceed();
            } finally {
                // Logs whatever was captured even if reading the entity failed (e.g. malformed payload),
                // so a deserialization error does not leave the request entirely unlogged
                String body = getBodyFiltered(outputStream, getBodyFiltersRequest());
                if (getBodyLoggingRequest().contains(LogType.LOG) && isNotBlank(body)) {
                    logRequest(body);
                }
                if (getBodyLoggingRequest().contains(LogType.MDC)) {
                    requestContext.setProperty(REQUEST_BODY_PROPERTY, body);
                }
            }
        } else {
            entity = context.proceed();
        }

        return entity;
    }

    /**
     * Logs the request received by the server.
     * Note that the request method and URI must have been stored in MDC before calling this method.
     *
     * @param requestBody The request body to be logged
     */
    protected void logRequest(String requestBody) {
        log.info("Received {} {}{}{}",
                getMdc(REQUEST_METHOD),
                getMdc(REQUEST_URI),
                isNotBlank(requestBody) ? LF : EMPTY,
                requestBody);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        long requestStartTime = Optional.ofNullable(requestContext.getProperty(REQUEST_TIME_PROPERTY))
                .map(Object::toString)
                .map(Long::parseLong)
                .orElse(nanoTime());
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
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            String responseBody = null;
            try {
                if (!getBodyLoggingResponse().isEmpty()) {
                    TeeOutputStream teeOutputStream = new TeeOutputStream(
                            context.getOutputStream(),
                            new BoundedOutputStream(outputStream, getBodyLimitResponse()));
                    context.setOutputStream(teeOutputStream);
                    context.proceed();
                    String body = getBodyFiltered(outputStream, getBodyFiltersResponse());
                    if (getBodyLoggingResponse().contains(LogType.MDC)) {
                        putMdc(RESPONSE_BODY, body);
                    }
                    if (getBodyLoggingResponse().contains(LogType.LOG)) {
                        responseBody = body;
                    }
                } else {
                    context.proceed();
                }
            } finally {
                // Always log and clean up MDC, even if writing the response body fails
                // (e.g. client disconnection), to avoid leaking context fields onto a pooled thread
                logResponse(requireNonNullElse(responseBody, EMPTY));
            }
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
            if (getBodyLoggingRequest().contains(LogType.MDC)) {
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
     * Applies the defined body filters to the given payload.
     *
     * @param outputStream The payload to be filtered
     * @return The payload filtered
     */
    protected String getBodyFiltered(ByteArrayOutputStream outputStream, Set<LoggedBodyFilter> filters) {
        String body = outputStream.toString(UTF_8);
        if (filters.isEmpty()) {
            return body;
        }

        StringBuilder bodyBuilder = new StringBuilder(body);
        filters.forEach(filter -> filter.filter(bodyBuilder));
        return bodyBuilder.toString();
    }

    /**
     * Body logging configuration resolved for both directions of a given resource method.
     *
     * @param request  The body logging configuration applicable to the request, if any
     * @param response The body logging configuration applicable to the response, if any
     */
    private record BodyConfiguration(Optional<LoggedBody> request, Optional<LoggedBody> response) {

    }

    /**
     * Cache of the body logging configuration resolved for each resource method, as it only depends on
     * the (immutable) annotations present on the matched class/method and not on request data, avoiding
     * a reflection-based annotation lookup on every single request to the same resource method.
     */
    protected final Map<Method, BodyConfiguration> bodyConfigurationCache = new ConcurrentHashMap<>();

    /**
     * Gets the most specific body logging configuration for the given target (request or response).
     * If multiple configurations are defined, the one specifically targeting the given target is returned.
     * Otherwise, the configuration targeting both request and response is returned if present.
     * <p>
     * The result is resolved once per resource method and cached, as reflection-based annotation
     * lookups are expensive to repeat on every request.
     *
     * @param target The target for which to find the body logging configuration
     * @return The most specific body logging configuration if present
     */
    protected Optional<LoggedBody> getBodyConfiguration(Direction target) {
        BodyConfiguration configuration = bodyConfigurationCache.computeIfAbsent(resourceInfo.getResourceMethod(),
                method -> new BodyConfiguration(resolveBodyConfiguration(REQUEST), resolveBodyConfiguration(RESPONSE)));
        return target == REQUEST ? configuration.request() : configuration.response();
    }

    /**
     * Finds the most specific body logging configuration for the given target (request or response)
     * by walking the annotations present on the resource class/method matched by the current request.
     *
     * @param target The target for which to find the body logging configuration
     * @return The most specific body logging configuration if present
     */
    protected Optional<LoggedBody> resolveBodyConfiguration(Direction target) {
        LoggedBody both = null;
        for (LoggedBody logging : getAnnotation(resourceInfo, LoggedBody.class, Logged.class, Logged::value)) {
            List<Direction> targets = Arrays.asList(logging.targets());
            if (targets.size() == 1 && targets.getFirst() == target) {
                return Optional.of(logging);
            }
            if (targets.size() == 2 && targets.contains(REQUEST) && targets.contains(RESPONSE)) {
                both = logging;
            }
        }
        return Optional.ofNullable(both);
    }

    /**
     * Gets how the request body must be logged.
     *
     * @return The types of logging to be done
     */
    protected Set<LogType> getBodyLoggingRequest() {
        return getBodyConfiguration(REQUEST)
                .map(LoggedBody::value)
                .stream()
                .flatMap(Stream::of)
                .collect(toSet());
    }

    /**
     * Gets how the response body must be logged.
     *
     * @return The types of logging to be done
     */
    protected Set<LogType> getBodyLoggingResponse() {
        return getBodyConfiguration(RESPONSE)
                .map(LoggedBody::value)
                .stream()
                .flatMap(Stream::of)
                .collect(toSet());
    }

    /**
     * Gets the size limit of the request body to be logged or -1 if no limit is applied.
     *
     * @return The maximum size of the body to be logged in bytes
     */
    protected int getBodyLimitRequest() {
        return getBodyConfiguration(REQUEST)
                .map(LoggedBody::limit)
                .orElse(-1);
    }

    /**
     * Gets the size limit of the response body to be logged or -1 if no limit is applied.
     *
     * @return The maximum size of the body to be logged in bytes
     */
    protected int getBodyLimitResponse() {
        return getBodyConfiguration(RESPONSE)
                .map(LoggedBody::limit)
                .orElse(-1);
    }

    /**
     * Gets the filters that must be applied before logging the request body.
     *
     * @return The list of filters to be applied
     */
    protected Set<LoggedBodyFilter> getBodyFiltersRequest() {
        return getBodyFilters(getBodyConfiguration(REQUEST)
                .map(LoggedBody::filters)
                .stream());
    }

    /**
     * Gets the filters that must be applied before logging the response body.
     *
     * @return The list of filters to be applied
     */
    protected Set<LoggedBodyFilter> getBodyFiltersResponse() {
        return getBodyFilters(getBodyConfiguration(RESPONSE)
                .map(LoggedBody::filters)
                .stream());
    }

    /**
     * Gets the filters instances that must be applied before logging a body.
     * Instantiates the given filters if not already done (caching).
     *
     * @param filtersType The stream of filters classes to be instantiated
     * @return The list of filters to be applied
     */
    protected Set<LoggedBodyFilter> getBodyFilters(Stream<Class<? extends LoggedBodyFilter>[]> filtersType) {
        return filtersType
                .flatMap(Stream::of)
                .map(this::getBodyFiltersInstance)
                .collect(toSet());
    }

    /**
     * No-op filter cached for a body filter class that failed to be instantiated, so that failure is
     * remembered instead of being retried (and re-logged) on every single request to the resource
     * method referencing it.
     * <p>
     * A plain {@code null} cannot be used for that purpose: {@link ConcurrentHashMap#computeIfAbsent}
     * does not record a mapping when the function returns {@code null} (see its Javadoc), so returning
     * {@code null} on failure previously caused the reflective instantiation (and the {@code log.error}
     * call) to be repeated on every request instead of once.
     */
    protected static final LoggedBodyFilter FAILED_BODY_FILTER = body -> {
        // No-op: the class could not be instantiated, see the error logged once at that time
    };

    /**
     * Creates a new instance of the given body filter type.
     *
     * @param type The body filter class to be instantiated
     * @param <T>  The body filter type
     * @return The instance created, or {@link #FAILED_BODY_FILTER} if it failed
     */
    protected <T extends LoggedBodyFilter> LoggedBodyFilter getBodyFiltersInstance(Class<T> type) {
        return filtersCache.computeIfAbsent(type, ignored -> {
            try {
                return type.getConstructor().newInstance();
            } catch (Exception e) {
                log.error("Unable to instantiate body filter {}, it will be skipped for every subsequent request", type, e);
                return FAILED_BODY_FILTER;
            }
        });
    }

    /**
     * Removes every MDC key put through {@link #putMdc(String, String)} for the current request (see
     * {@link #MDC_KEYS_PROPERTY}), which covers every entry put by this provider itself as well as any
     * subclass following the same convention.
     * <p>
     * Also sweeps the fixed set of fields in {@link #mdcFields} as a safety net, in case a subclass
     * still puts one of those directly through {@link MDC#put(String, String)} (as opposed to
     * {@link #putMdc(LoggedField, String)}) after registering it there.
     */
    protected void cleanupMdc() {
        mdcFields.values().forEach(MDC::remove);
        getMdcKeys().forEach(MDC::remove);
    }

}