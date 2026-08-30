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
     */
    protected static final String REQUEST_BODY_PROPERTY = "request-body";

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
     * Puts a diagnostic context value identified by the given field into the current thread's context map.
     *
     * @param field The field for which put the given value
     * @param value The value to be associated with the given field
     */
    protected void putMdc(LoggedField field, String value) {
        if (value != null) {
            MDC.put(mdcFields.get(field.name()), value);
        }
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
                            MDC.put(mdcKey, sanitize(entry.getValue().getFirst()));
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
                        .ifPresent(value -> MDC.put(mapping.mdcPrefix() + mapping.mdcKey(), sanitize(value)));
            }
        }
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
                .orElse(randomUUID().toString());
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
        getMergedMappings(resourceInfo).stream()
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

        // Logs directly from filter in case no response body is present as aroundWriteTo will not be called
        if (!getBodyLoggingResponse().isEmpty() && !responseContext.hasEntity()) {
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
     * Logs the response sent by the server.
     * Note that the response status and duration must have been stored in MDC before calling this method.
     *
     * @param responseBody The response body to be logged
     */
    protected void logResponse(String responseBody) {
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
     * Finds the most specific body logging configuration for the given target (request or response).
     * If multiple configurations are defined, the one specifically targeting the given target is returned.
     * Otherwise, the configuration targeting both request and response is returned if present.
     *
     * @param target The target for which to find the body logging configuration
     * @return The most specific body logging configuration if present
     */
    protected Optional<LoggedBody> getBodyConfiguration(Direction target) {
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
                .filter(Objects::nonNull)
                .collect(toSet());
    }

    /**
     * Creates a new instance of the given body filter type.
     *
     * @param type The body filter class to be instantiated
     * @param <T>  The body filter type
     * @return The instance created or {@code null} if it failed
     */
    protected <T extends LoggedBodyFilter> LoggedBodyFilter getBodyFiltersInstance(Class<T> type) {
        return filtersCache.computeIfAbsent(type, ignored -> {
            try {
                return type.getConstructor().newInstance();
            } catch (Exception e) {
                log.error("Unable to instantiate body filter {}", type, e);
                return null;
            }
        });
    }

    /**
     * Removes all MDC fields defined in
     * <ul>
     *     <li>{@link #filter(ContainerRequestContext)}</li>
     *     <li>{@link #aroundReadFrom(ReaderInterceptorContext)}</li>
     *     <li>{@link #filter(ContainerRequestContext, ContainerResponseContext)}</li>
     *     <li>{@link #aroundWriteTo(WriterInterceptorContext)}</li>
     * </ul>
     */
    protected void cleanupMdc() {
        mdcFields.values().forEach(MDC::remove);
    }

}