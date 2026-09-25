package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedFilter.REQUEST_ID_HEADER;
import static com.chavaillaz.jakarta.rs.LoggedSupport.levelOf;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;
import static org.apache.commons.lang3.StringUtils.isBlank;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;

import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import com.chavaillaz.jakarta.rs.capture.BoundedLoggedBodyCapture;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.CredentialNames;

/**
 * Configuration of a {@link LoggedFilter}: the names of its MDC entries, how it identifies a request and
 * whether it returns that identifier to the caller, which parameters it keeps out of the logs, the level it
 * logs a request at, and how it captures bodies.
 * <p>
 * Immutable, and built through {@link #builder()}. A container instantiates a provider through its
 * no-argument constructor, so a subclass passes its configuration from there:
 * <pre>{@code
 * @Provider
 * public class ApplicationLoggedFilter extends LoggedFilter {
 *
 *     public ApplicationLoggedFilter() {
 *         super(LoggedFilterConfiguration.builder()
 *                 .fieldName(LoggedField.REQUEST_ID, "trace-id")
 *                 .requestIdHeader("X-Trace-ID")
 *                 .build());
 *     }
 *
 * }
 * }</pre>
 * An application registering its providers explicitly passes it to
 * {@link LoggedFilter#LoggedFilter(LoggedFilterConfiguration)} instead.
 * <p>
 * It applies to every resource the provider logs: what varies from a resource to another - which bodies are
 * logged and how they are filtered, which parameters are mapped - is declared on the resource itself, with
 * {@link LoggedBody} and {@link LoggedMapping}.
 */
public final class LoggedFilterConfiguration {

    private static final LoggedFilterConfiguration DEFAULTS = builder().build();

    private final Map<LoggedField, String> fieldNames;
    private final String requestIdHeader;
    private final Function<ContainerRequestContext, @Nullable String> requestId;
    private final boolean requestIdReturned;
    private final BiPredicate<MappingType, String> sensitiveParameters;
    private final IntFunction<@Nullable Level> responseLevel;
    private final IntFunction<LoggedBodyCapture> bodyCapture;

    private LoggedFilterConfiguration(Builder builder) {
        this.fieldNames = unmodifiableMap(new EnumMap<>(builder.fieldNames));
        String header = builder.requestIdHeader;
        this.requestIdHeader = header;
        this.requestId = builder.requestId != null ? builder.requestId : request -> requestIdFromHeader(request, header);
        this.requestIdReturned = builder.requestIdReturned;
        this.sensitiveParameters = builder.sensitiveParameters;
        this.responseLevel = builder.responseLevel;
        this.bodyCapture = builder.bodyCapture;
    }

    /**
     * Gets the default configuration, used by {@link LoggedFilter#LoggedFilter()}.
     *
     * @return The configuration every setting of {@link Builder} documents the default of
     */
    public static LoggedFilterConfiguration defaults() {
        return DEFAULTS;
    }

    /**
     * Creates a builder starting from the default configuration.
     *
     * @return The builder created
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Indicates whether the value of the given parameter carries a credential by the conventions callers
     * follow to name one, as listed by {@link CredentialNames}: the default of
     * {@link Builder#sensitiveParameters(BiPredicate)}, to compose with when extending it.
     * <p>
     * Path parameters are never reported: their names are chosen by the application itself, not by whoever
     * calls it, so there is no equivalent list of names that "just happen" to carry a credential.
     *
     * @param type The type of the parameter
     * @param name The name of the parameter
     * @return {@code true} if the parameter is named like one carrying a credential, {@code false} otherwise
     */
    public static boolean isCredential(MappingType type, String name) {
        return switch (type) {
            case HEADER -> CredentialNames.isHeader(name);
            case QUERY -> CredentialNames.isQueryParameter(name);
            case PATH -> false;
        };
    }

    /**
     * Gets the name of the MDC entry of the given field.
     *
     * @param field The field to get the name of
     * @return The name of its MDC entry, or {@code null} for a field left out (see {@link Builder#withoutField(LoggedField)})
     */
    public @Nullable String fieldName(LoggedField field) {
        return fieldNames.get(field);
    }

    /**
     * Gets the names of the MDC entries of the fields, without the fields left out.
     *
     * @return The names of the MDC entries, by field
     */
    Map<LoggedField, String> fieldNames() {
        return fieldNames;
    }

    /**
     * Gets the identifier of the given request, as the configured strategy obtains it.
     *
     * @param request The context of the request received
     * @return The identifier of the request, not sanitized yet, and {@code null} or blank if there is none
     *         (see {@link RequestDescriber#requestIdOf(String)})
     */
    @Nullable String requestIdOf(ContainerRequestContext request) {
        return requestId.apply(request);
    }

    /**
     * Gets the name of the header the identifier of a request is returned to the caller in.
     *
     * @return The name of the header, or {@code null} if the identifier is not returned
     */
    @Nullable String returnedRequestIdHeader() {
        return requestIdReturned ? requestIdHeader : null;
    }

    /**
     * Indicates whether the value of the given parameter must be kept out of the logs.
     *
     * @param type The type of the parameter
     * @param name The name of the parameter
     * @return {@code true} if its value must be kept out of the logs, {@code false} otherwise
     */
    boolean isSensitive(MappingType type, String name) {
        return sensitiveParameters.test(type, name);
    }

    /**
     * Gets the level a request answered with the given status is logged at.
     *
     * @param status The status the request was answered with, {@code 0} when unknown
     * @return The level to log the request at, never {@code null}
     */
    Level responseLevel(int status) {
        return requireNonNullElseGet(responseLevel.apply(status), () -> levelOf(status));
    }

    /**
     * Creates the capture of a body.
     *
     * @param limit The maximum size of the body to capture in bytes, or {@code -1} for no limit
     * @return The capture created
     */
    LoggedBodyCapture createBodyCapture(int limit) {
        return bodyCapture.apply(limit);
    }

    /**
     * Gets the identifier of the given request from the given header.
     *
     * @param request The context of the request received
     * @param header  The name of the header carrying the identifier
     * @return The value of the header, {@code null} if the request has none
     */
    private static @Nullable String requestIdFromHeader(ContainerRequestContext request, String header) {
        MultivaluedMap<String, String> headers = request.getHeaders();
        return headers == null ? null : headers.getFirst(header);
    }

    /**
     * Builder of {@link LoggedFilterConfiguration}, starting from the default of every setting.
     */
    public static final class Builder {

        private final Map<LoggedField, String> fieldNames = new EnumMap<>(LoggedField.class);
        private String requestIdHeader = REQUEST_ID_HEADER;
        private @Nullable Function<ContainerRequestContext, @Nullable String> requestId;
        private boolean requestIdReturned = true;
        private BiPredicate<MappingType, String> sensitiveParameters = LoggedFilterConfiguration::isCredential;
        private IntFunction<@Nullable Level> responseLevel = LoggedSupport::levelOf;
        private IntFunction<LoggedBodyCapture> bodyCapture = BoundedLoggedBodyCapture::new;

        private Builder() {
            for (LoggedField field : LoggedField.values()) {
                fieldNames.put(field, field.getDefaultField());
            }
        }

        /**
         * Sets the name of the MDC entry of the given field, to align it with the names other applications
         * use, or with the schema of whatever the logs are shipped to. Defaults to
         * {@link LoggedField#getDefaultField()}.
         *
         * @param field The field to name
         * @param name  The name of its MDC entry
         * @return This builder
         * @throws IllegalArgumentException if the name is blank, as MDC cannot hold an entry without one
         */
        public Builder fieldName(LoggedField field, String name) {
            requireNonNull(field, "The field is required");
            if (isBlank(name)) {
                throw new IllegalArgumentException("The name of the MDC entry of " + field + " is required, leave the field out instead");
            }
            fieldNames.put(field, name);
            return this;
        }

        /**
         * Leaves the given field out of MDC altogether - along with the log lines reading it back from there,
         * such as the status in the {@code "Processed ..."} line and the level it decides.
         *
         * @param field The field to leave out
         * @return This builder
         */
        public Builder withoutField(LoggedField field) {
            fieldNames.remove(requireNonNull(field, "The field is required"));
            return this;
        }

        /**
         * Sets the header a request identifier is read from, and returned to the caller in (see
         * {@link #withoutReturnedRequestId()}). Defaults to {@value LoggedFilter#REQUEST_ID_HEADER}.
         * <p>
         * The identifier is sanitized and truncated to 128 characters, and a random UUID is generated for a
         * request without one. Note that it is taken from the client as-is beyond that: it is a correlation
         * hint, never an authenticated value, so nothing downstream should treat two requests sharing one as
         * necessarily related. See {@link #requestId(Function)} to generate it server-side instead.
         *
         * @param header The name of the header
         * @return This builder
         * @throws IllegalArgumentException if the name is blank
         */
        public Builder requestIdHeader(String header) {
            if (isBlank(header)) {
                throw new IllegalArgumentException("The name of the request identifier header is required");
            }
            this.requestIdHeader = header;
            return this;
        }

        /**
         * Sets how the identifier of a request is obtained, for example to always generate it server-side
         * when the callers are untrusted:
         * <pre>{@code
         * .requestId(request -> UUID.randomUUID().toString())
         * }</pre>
         * The identifier obtained is sanitized and truncated like one read from the header, and a request
         * the strategy obtains none for - returning {@code null} or a blank value, or failing - gets a
         * random UUID. Defaults to reading the {@link #requestIdHeader(String) request identifier header}.
         *
         * @param strategy The strategy getting the identifier of the given request
         * @return This builder
         */
        public Builder requestId(Function<ContainerRequestContext, @Nullable String> strategy) {
            this.requestId = requireNonNull(strategy, "The request identifier strategy is required");
            return this;
        }

        /**
         * Stops returning the identifier a request was logged under to the caller.
         * <p>
         * Returned by default, in the {@link #requestIdHeader(String) request identifier header}, unless the
         * response already carries that header: without it, the identifier tying every log line of a request
         * together exists only on the server, and a caller reporting "your API returned a 500 at about
         * 14:32" leaves whoever picks up the report searching by timestamp, where one quoting it points
         * straight at the request.
         *
         * @return This builder
         */
        public Builder withoutReturnedRequestId() {
            this.requestIdReturned = false;
            return this;
        }

        /**
         * Sets which parameters have their value kept out of the logs: an automatic {@link LoggedMapping}
         * skips them entirely, and the query parameters logged as {@link LoggedField#REQUEST_PARAMETERS}
         * have their value masked while keeping their name. An explicit mapping naming a parameter is a
         * deliberate decision and is left alone by both.
         * <p>
         * Defaults to {@link #isCredential(MappingType, String)}, which only knows what callers
         * conventionally name their secrets. Compose with it to extend (or restrict) it for an application
         * that knows its own, for example to also mask a query parameter carrying a signed URL token:
         * <pre>{@code
         * .sensitiveParameters((type, name) -> isCredential(type, name)
         *         || (type == QUERY && "url-signature".equalsIgnoreCase(name)))
         * }</pre>
         *
         * @param predicate Whether the value of the parameter of the given type and name must be kept out of the logs
         * @return This builder
         */
        public Builder sensitiveParameters(BiPredicate<MappingType, String> predicate) {
            this.sensitiveParameters = requireNonNull(predicate, "The sensitive parameters predicate is required");
            return this;
        }

        /**
         * Sets the level a request is logged at once answered, given the status it was answered with, for
         * example to leave an expected {@code 404} at {@code INFO}. Defaults to {@link LoggedSupport#levelOf(int)},
         * see there why it is not simply {@code INFO}.
         * <p>
         * The status is read back from MDC, so a request whose {@link LoggedField#RESPONSE_STATUS} field is
         * left out is given {@code 0}. A status the function returns {@code null} for is logged at its
         * default level.
         *
         * @param levels The level to log a request answered with the given status at
         * @return This builder
         */
        public Builder responseLevel(IntFunction<@Nullable Level> levels) {
            this.responseLevel = requireNonNull(levels, "The response level function is required");
            return this;
        }

        /**
         * Sets how the bodies are captured, given the maximum size to capture in bytes, or {@code -1} for no
         * limit. Defaults to capturing them in memory ({@link BoundedLoggedBodyCapture}).
         * <p>
         * This is the extension point for the mechanics of body capture itself, as opposed to
         * {@link LoggedBodyFilter}, which only transforms content already captured: plug in a different
         * strategy, for example spilling very large bodies to a temporary file instead of memory.
         *
         * @param factory The creation of the capture of a body keeping at most the given number of bytes
         * @return This builder
         */
        public Builder bodyCapture(IntFunction<LoggedBodyCapture> factory) {
            this.bodyCapture = requireNonNull(factory, "The body capture factory is required");
            return this;
        }

        /**
         * Builds the configuration set on this builder.
         *
         * @return The configuration built
         * @throws IllegalStateException if two fields are given the same name, as the entry of one would
         *                               then silently overwrite the entry of the other
         */
        public LoggedFilterConfiguration build() {
            Map<String, LoggedField> fieldsByName = new HashMap<>();
            fieldNames.forEach((field, name) -> {
                LoggedField other = fieldsByName.putIfAbsent(name, field);
                if (other != null) {
                    throw new IllegalStateException("The fields " + other + " and " + field + " are both named " + name);
                }
            });
            return new LoggedFilterConfiguration(this);
        }

    }

}
