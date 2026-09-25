package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_PARAMETERS;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_CLASS;
import static com.chavaillaz.jakarta.rs.LoggedField.RESOURCE_METHOD;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static com.chavaillaz.jakarta.rs.filter.MaskingBodyFilter.DEFAULT_MASK;
import static java.lang.Character.isHighSurrogate;
import static java.lang.String.join;
import static java.util.Map.Entry.comparingByKey;
import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.UriInfo;
import org.jspecify.annotations.Nullable;

/**
 * Describes a request received and the resource matched for it, as the fields of {@link LoggedField}
 * {@link LoggedFilter} puts in MDC once a request starts.
 * <p>
 * All of it but the resource comes from the client, and is treated as such: whatever reaches the logs is
 * sanitized first (see {@link #sanitize(String)}), the identifier a client supplies is bounded (see
 * {@link #requestIdOf(String)}), and the value of a query parameter carrying a credential is masked.
 */
final class RequestDescriber {

    /**
     * Maximum length kept from a client-supplied {@value LoggedFilter#REQUEST_ID_HEADER} header before it
     * is stored in MDC.
     * <p>
     * A container's overall header size limit is shared across every header of the request, not applied
     * individually, so without a limit of its own a client can inflate every single log line written
     * during the request by supplying an excessively long identifier.
     */
    static final int REQUEST_ID_MAX_LENGTH = 128;

    /**
     * Pattern matching control characters (e.g. CR, LF) that must be removed from client-controlled
     * input (headers, query or path parameters) before it is stored in MDC, to prevent an attacker
     * from forging fake log entries or corrupting the log line (log injection).
     * <p>
     * Covers every control character Unicode defines, and not only the ASCII ones {@code \p{Cntrl}} stands
     * for: the C1 set is what the bytes {@code 0x80} to {@code 0x9F} of an ISO-8859-1 header decode to, and
     * holds {@code U+0085}, a line break to some log viewers, and {@code U+009B}, which terminals read as the
     * start of an escape sequence. Also covers {@code U+2028}/{@code U+2029}, which several log viewers and
     * JavaScript-based log pipelines treat as line breaks.
     */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");

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
     * Replaces the control characters (e.g. CR, LF) of the given value with spaces, see
     * {@link LoggedFilter#sanitize(String)}.
     *
     * @param value The value to sanitize
     * @return The sanitized value, or {@code null} if the given value was {@code null}
     */
    static @Nullable String sanitize(@Nullable String value) {
        return value == null ? null : CONTROL_CHARACTERS.matcher(value).replaceAll(" ");
    }

    /**
     * Gets the identifier to log a request under from the one obtained for it - read from its
     * {@value LoggedFilter#REQUEST_ID_HEADER} header by default - sanitized and truncated to
     * {@link #REQUEST_ID_MAX_LENGTH} characters, or a random UUID when none was obtained.
     *
     * @param obtained The identifier obtained for the request, {@code null} if there is none
     * @return The request identifier, never blank
     */
    static String requestIdOf(@Nullable String obtained) {
        // Sanitized before being checked, as sanitizing turns an identifier made of nothing but control
        // characters blank, and a blank value is never put in MDC
        String requestId = sanitize(obtained);
        return isNotBlank(requestId) ? truncate(requestId) : randomUUID().toString();
    }

    /**
     * Truncates the given request identifier to {@link #REQUEST_ID_MAX_LENGTH} characters, leaving out
     * whole a character the limit would otherwise cut in half: a character outside the Basic Multilingual
     * Plane takes two {@code char}s, and keeping only the first of them leaves a string that is no longer
     * valid text, which an appender encoding it then writes as a replacement character, or rejects.
     *
     * @param requestId The request identifier to truncate
     * @return The identifier, truncated if it was longer than allowed
     */
    private static String truncate(String requestId) {
        if (requestId.length() <= REQUEST_ID_MAX_LENGTH) {
            return requestId;
        }
        int end = isHighSurrogate(requestId.charAt(REQUEST_ID_MAX_LENGTH - 1))
                ? REQUEST_ID_MAX_LENGTH - 1
                : REQUEST_ID_MAX_LENGTH;
        return requestId.substring(0, end);
    }

    /**
     * Describes the given request and the resource matched for it, handing each field describing them over
     * to the given output.
     *
     * @param request   The context of the request received
     * @param resource  The resource matched for the request
     * @param requestId The identifier obtained for the request, {@code null} if there is none, made fit to
     *                  be logged here as it may come from the client (see {@link #requestIdOf(String)})
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
