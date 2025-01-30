package com.chavaillaz.jakarta.rs;

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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.chavaillaz.jakarta.rs.Logged.LogType;
import jakarta.inject.Inject;
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

    @Context
    ResourceInfo resourceInfo;

    @Inject
    ContainerRequestContext requestContext;

    /**
     * Gets the annotation type used to activate this provider.
     *
     * @return The annotation type
     */
    protected Optional<Logged> getAnnotation() {
        return LoggedUtils.getAnnotation(resourceInfo, Logged.class);
    }

    /**
     * Puts a diagnostic context value identified by the given field into the current thread's context map.
     *
     * @param field The field for which put the given value
     * @param value The value to be associated with the given field
     */
    private void putMdc(LoggedField field, String value) {
        if (value != null) {
            MDC.put(mdcFields.get(field.name()), value);
        }
    }

    /**
     * Gets a diagnostic context value identified by the given field from the current thread's context map.
     *
     * @param field The field for which get the value
     * @return The value associated with the given field
     */
    private String getMdc(LoggedField field) {
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
        putMdc(REQUEST_ID, getRequestId(requestContext));
        putMdc(REQUEST_URI, requestContext.getUriInfo().getPath());
        putMdc(REQUEST_PARAMETERS, requestContext.getUriInfo()
                .getQueryParameters()
                .entrySet()
                .stream()
                .sorted(comparingByKey())
                .map(entry -> entry.getKey() + "=" + join(",", entry.getValue()))
                .collect(joining("&")));
        putMdc(REQUEST_METHOD, requestContext.getMethod());
        Optional.ofNullable(resourceInfo.getResourceClass())
                .map(Class::getSimpleName)
                .ifPresent(value -> putMdc(RESOURCE_CLASS, value));
        Optional.ofNullable(resourceInfo.getResourceMethod())
                .map(Method::getName)
                .ifPresent(value -> putMdc(RESOURCE_METHOD, value));

        // Logs directly from filter in case no request body is expected as aroundReadFrom will not be called
        if (requestBodyLogging().contains(LogType.LOG) && !hasEntity(requestContext)) {
            logRequest(EMPTY);
        }
    }

    private boolean hasEntity(ContainerRequestContext requestContext) {
        return requestContext.hasEntity() && requestContext.getLength() != 0;
    }

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        Object entity;
        if (!requestBodyLogging().isEmpty()) {
            var outputStream = new ByteArrayOutputStream();
            var teeInputStream = new TeeInputStream(context.getInputStream(), new BoundedOutputStream(outputStream, limitBodyLogging()));
            context.setInputStream(teeInputStream);
            entity = context.proceed();
            String requestBody = filterBody(outputStream.toString());
            if (requestBodyLogging().contains(LogType.LOG) && isNotBlank(requestBody)) {
                logRequest(requestBody);
            }
            if (requestBodyLogging().contains(LogType.MDC)) {
                requestContext.setProperty(REQUEST_BODY_PROPERTY, requestBody);
            }
        } else {
            entity = context.proceed();
        }

        return entity;
    }

    private void logRequest(String requestBody) {
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
        if (!responseBodyLogging().isEmpty() && !responseContext.hasEntity()) {
            logResponse(EMPTY);
        }
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        try (var output = new ByteArrayOutputStream()) {
            String responseBody = null;
            if (!responseBodyLogging().isEmpty()) {
                var teeOutput = new TeeOutputStream(context.getOutputStream(), new BoundedOutputStream(output, limitBodyLogging()));
                context.setOutputStream(teeOutput);
                context.proceed();
                String body = filterBody(output.toString());
                if (responseBodyLogging().contains(LogType.MDC)) {
                    putMdc(RESPONSE_BODY, body);
                }
                if (responseBodyLogging().contains(LogType.LOG)) {
                    responseBody = body;
                }
            } else {
                context.proceed();
            }

            logResponse(requireNonNullElse(responseBody, EMPTY));
        } finally {
            cleanupMdc();
        }
    }

    private void logResponse(String responseBody) {
        try {
            if (requestBodyLogging().contains(LogType.MDC)) {
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
            MDC.remove(REQUEST_BODY.name());
        }
    }

    /**
     * Gets how the request body must be logged.
     *
     * @return The types of logging to be done
     */
    protected Set<LogType> requestBodyLogging() {
        return getAnnotation()
                .map(Logged::requestBody)
                .stream()
                .flatMap(Stream::of)
                .collect(toSet());
    }

    /**
     * Gets how the response body must be logged.
     *
     * @return The types of logging to be done
     */
    protected Set<LogType> responseBodyLogging() {
        return getAnnotation()
                .map(Logged::responseBody)
                .stream()
                .flatMap(Stream::of)
                .collect(toSet());
    }

    /**
     * Gets the size limit of the body to be logged or -1 if no limit is applied.
     *
     * @return The maximum size of the body to be logged in bytes
     */
    protected int limitBodyLogging() {
        return getAnnotation()
                .map(Logged::limitBody)
                .orElse(-1);
    }

    /**
     * Gets the filters that must be applied before logging the request or response body.
     *
     * @return The list of filters to be applied
     */
    protected Set<LoggedBodyFilter> filtersBody() {
        return getAnnotation()
                .map(Logged::filtersBody)
                .stream()
                .flatMap(Stream::of)
                .map(this::instantiateFilter)
                .filter(Objects::nonNull)
                .collect(toSet());
    }

    /**
     * Applies the defined body filters to the given payload.
     *
     * @param payload The payload to be filtered
     * @return The payload filtered
     */
    protected String filterBody(String payload) {
        for (LoggedBodyFilter filter : filtersBody()) {
            payload = filter.filterBody(payload);
        }
        return payload;
    }

    /**
     * Creates a new instance of the given body filter type.
     *
     * @param type The body filter class to be instantiated
     * @param <T>  The body filter type
     * @return The instance created or {@code null} if it failed
     */
    protected <T extends LoggedBodyFilter> T instantiateFilter(Class<T> type) {
        try {
            return type.getConstructor().newInstance();
        } catch (Exception e) {
            log.error("Unable to instantiate request body filter {}", type, e);
            return null;
        }
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