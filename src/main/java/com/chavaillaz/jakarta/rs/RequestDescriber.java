package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_PARAMETERS;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_CLASS;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static com.chavaillaz.jakarta.rs.filter.MaskingBodyFilter.DEFAULT_MASK;
import static com.chavaillaz.jakarta.rs.internal.Sanitizer.requestIdOf;
import static com.chavaillaz.jakarta.rs.internal.Sanitizer.sanitize;
import static java.lang.String.join;
import static java.util.Map.Entry.comparingByKey;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.EMPTY;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import com.chavaillaz.jakarta.rs.internal.Sanitizer;

/**
 * Describes a request received and the resource matched for it, as the fields of {@link LoggedField}
 * {@link LoggedFilter} puts in MDC once a request starts.
 * <p>
 * All of it but the resource comes from the client, and is treated as such: whatever reaches the logs is
 * sanitized first (see {@link Sanitizer#sanitize(String)}), the identifier a client supplies is bounded
 * (see {@link Sanitizer#requestIdOf(String)}), and the value of a query parameter carrying a credential is
 * masked.
 */
final class RequestDescriber {

    private final BiPredicate<MappingType, String> sensitive;

    /**
     * Creates a describer masking the parameters the given predicate reports.
     *
     * @param sensitive Whether the value of the parameter of the given type and name must be kept out of the logs
     */
    RequestDescriber(BiPredicate<MappingType, String> sensitive) {
        this.sensitive = sensitive;
    }

    /**
     * Describes the given request and the resource matched for it, handing each field describing them over
     * to the given output.
     *
     * @param request   The context of the request received
     * @param resource  The resource matched for the request
     * @param requestId The identifier obtained for the request, {@code null} if there is none, made fit to
     *                  be logged here as it may come from the client (see
     *                  {@link Sanitizer#requestIdOf(String)})
     * @param output    What to do with each field, given its value
     */
    void describe(ContainerRequestContext request, ResourceInfo resource, @Nullable String requestId, BiConsumer<LoggedField, @Nullable String> output) {
        UriInfo uriInfo = request.getUriInfo();
        output.accept(REQUEST_ID, requestIdOf(requestId));
        output.accept(REQUEST_URI, sanitize(uriInfo.getPath()));
        output.accept(REQUEST_PARAMETERS, sanitize(describeQuery(uriInfo.getQueryParameters())));
        output.accept(REQUEST_METHOD, sanitize(request.getMethod()));
        Class<?> resourceClass = resource.getResourceClass();
        if (resourceClass != null) {
            output.accept(RESOURCE_CLASS, resourceClass.getSimpleName());
        }
        Method resourceMethod = resource.getResourceMethod();
        if (resourceMethod != null) {
            output.accept(RESOURCE_METHOD, resourceMethod.getName());
        }
    }

    /**
     * Renders the given query parameters as a single, deterministically ordered string, masking the value
     * of those carrying a credential.
     * <p>
     * The parameter name is kept even when its value is masked, as the name is what is useful for
     * troubleshooting (knowing an {@code access_token} was supplied at all) and is not itself the secret.
     *
     * @param parameters The query parameters of the request, by name
     * @return The rendered query parameters, empty if the request has none
     */
    String describeQuery(Map<String, List<String>> parameters) {
        if (parameters.isEmpty()) {
            // Short-circuits the sorted stream below for the (very common) case of a request without
            // any query parameter, this method being on the path of every single request
            return EMPTY;
        }
        return parameters.entrySet()
                .stream()
                .sorted(comparingByKey())
                .map(entry -> entry.getKey() + "=" + (sensitive.test(QUERY, entry.getKey())
                        ? DEFAULT_MASK
                        : join(",", entry.getValue())))
                .collect(joining("&"));
    }

}
