package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.DURATION;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_CLASS;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_STATUS;
import static com.chavaillaz.jakarta.rs.LoggedField.getDefaultFields;
import static java.lang.String.valueOf;
import static java.lang.System.nanoTime;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.toSet;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.chavaillaz.jakarta.rs.Logged.LogType;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import org.apache.commons.io.IOUtils;
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
 * This filter can be activated using the annotation {@link Logged} on resources.
 */
@Logged
@Provider
public class LoggedFilter implements ContainerRequestFilter, ContainerResponseFilter {

    protected static final Logger log = LoggerFactory.getLogger(LoggedFilter.class);

    /**
     * Name of the property stored in container context to compute the duration time.
     */
    protected static final String REQUEST_TIME = "request-time";

    /**
     * Names of MDC fields to be used for all logged fields.
     * Allows changes from children classes.
     */
    protected final Map<String, String> mdcFields = getDefaultFields();

    @Context
    ResourceInfo resourceInfo;

    @Context
    Providers providers;

    /**
     * Gets the given annotation from the resource method or class matched by the current request.
     *
     * @param resourceInfo The instance to access resource class and method
     * @param annotation   The annotation type to get
     * @param <A>          The annotation type
     * @return The annotation found or {@link Optional#empty} otherwise
     */
    protected static <A extends Annotation> Optional<A> getAnnotation(ResourceInfo resourceInfo, Class<A> annotation) {
        if (resourceInfo.getResourceMethod().isAnnotationPresent(annotation)) {
            return of(resourceInfo.getResourceMethod().getAnnotation(annotation));
        } else if (resourceInfo.getResourceClass().isAnnotationPresent(annotation)) {
            return of(resourceInfo.getResourceClass().getAnnotation(annotation));
        } else {
            return empty();
        }
    }

    /**
     * Gets the annotation type used to activate this filter.
     *
     * @return The annotation type
     */
    protected Optional<Logged> getAnnotation() {
        return getAnnotation(resourceInfo, Logged.class);
    }

    /**
     * Puts a diagnostic context value identified by the given field into the current thread's context map.
     *
     * @param field The field for which put the given value
     * @param value The value to put
     */
    private void putMdc(LoggedField field, String value) {
        MDC.put(mdcFields.get(field.name()), value);
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
        requestContext.setProperty(REQUEST_TIME, nanoTime());

        putMdc(REQUEST_ID, getRequestId(requestContext));

        putMdc(REQUEST_URI, requestContext.getUriInfo().getPath());

        putMdc(REQUEST_METHOD, requestContext.getMethod());

        Optional.ofNullable(resourceInfo.getResourceClass())
                .map(Class::getSimpleName)
                .ifPresent(value -> putMdc(RESOURCE_CLASS, value));

        Optional.ofNullable(resourceInfo.getResourceMethod())
                .map(Method::getName)
                .ifPresent(value -> putMdc(RESOURCE_METHOD, value));

        if (requestBodyLogging().contains(LogType.LOG) && requestContext.hasEntity()) {
            log.info("Received {} {}\n{}",
                    requestContext.getMethod(),
                    requestContext.getUriInfo().getPath(),
                    getRequestBody(requestContext));
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        long requestStartTime = Optional.ofNullable(requestContext.getProperty(REQUEST_TIME))
                .map(Object::toString)
                .map(Long::parseLong)
                .orElse(nanoTime());
        long duration = (nanoTime() - requestStartTime) / 1_000_000;
        putMdc(DURATION, valueOf(duration));

        putMdc(RESPONSE_STATUS, valueOf(responseContext.getStatus()));

        if (requestBodyLogging().contains(LogType.MDC) && requestContext.hasEntity()) {
            putMdc(REQUEST_BODY, getRequestBody(requestContext));
        }

        String responseBody = "";
        if (!responseBodyLogging().isEmpty() && responseContext.hasEntity()) {
            String body = getResponseBody(responseContext);
            if (responseBodyLogging().contains(LogType.MDC)) {
                putMdc(RESPONSE_BODY, body);
            }
            if (responseBodyLogging().contains(LogType.LOG)) {
                responseBody = "\n" + body;
            }
        }

        log.info("Processed {} {} with status {} in {}ms{}",
                requestContext.getMethod(),
                requestContext.getUriInfo().getPath(),
                responseContext.getStatus(),
                duration,
                responseBody);

        cleanupMdc();
    }

    /**
     * Extracts the request body as {@link String}.
     *
     * @param requestContext The context of the request
     * @return The request body or the message of the exception thrown
     */
    protected String getRequestBody(ContainerRequestContext requestContext) {
        try (BufferedInputStream stream = new BufferedInputStream(requestContext.getEntityStream())) {
            String payload = IOUtils.toString(stream, UTF_8);
            requestContext.setEntityStream(IOUtils.toInputStream(payload, UTF_8));
            return filterBody(payload);
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /**
     * Extracts the response body and parses it to {@link String} using the right body writer.
     *
     * @param responseContext The context of the response
     * @return The response body or the message of the exception thrown
     */
    protected String getResponseBody(ContainerResponseContext responseContext) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Class<?> entityClass = responseContext.getEntityClass();
            Type entityType = responseContext.getEntityType();
            Annotation[] entityAnnotations = responseContext.getEntityAnnotations();
            MediaType mediaType = responseContext.getMediaType();
            // Get the right body writer to be used for the entity in response
            var bodyWriter = (MessageBodyWriter<Object>) providers.getMessageBodyWriter(
                    entityClass,
                    entityType,
                    entityAnnotations,
                    mediaType
            );
            bodyWriter.writeTo(
                    responseContext.getEntity(),
                    entityClass,
                    entityType,
                    entityAnnotations,
                    mediaType,
                    responseContext.getHeaders(),
                    outputStream
            );
            return filterBody(outputStream.toString());
        } catch (IOException e) {
            return e.getMessage();
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
     * Gets the filters that must be applied before logging the request or response body.
     *
     * @return The list of filters to be applied
     */
    protected Set<LoggedBodyFilter> filersBody() {
        return getAnnotation()
                .map(Logged::filersBody)
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
        for (LoggedBodyFilter filter : filersBody()) {
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
     * Removes all MDC fields defined in {@link #filter(ContainerRequestContext)}
     * and {@link #filter(ContainerRequestContext, ContainerResponseContext)}.
     */
    protected void cleanupMdc() {
        mdcFields.values().forEach(MDC::remove);
    }

}