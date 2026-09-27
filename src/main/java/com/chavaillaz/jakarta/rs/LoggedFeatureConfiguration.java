package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedFeature.REQUEST_ID_HEADER;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;
import static org.apache.commons.lang3.StringUtils.isBlank;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedMap;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;

import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import com.chavaillaz.jakarta.rs.capture.BoundedBodyCapture;
import com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture;
import com.chavaillaz.jakarta.rs.client.LoggedClientFeature;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.CredentialNames;
import com.chavaillaz.jakarta.rs.internal.Sanitizer;

/**
 * Configuration of a {@link LoggedFeature}: the names of its MDC entries, how it identifies a request and
 * whether it returns that identifier to the caller, which parameters it keeps out of the logs, the level it
 * logs a request at, how it captures bodies, and the MDC entries the application adds.
 * <p>
 * Immutable, and built through {@link #builder()}. A {@link LoggedFeature} the container instantiates looks
 * its configuration up in the application, which declares it through a provider resolving it:
 * <pre>{@code
 * @Provider
 * public class LoggingConfiguration implements ContextResolver<LoggedFeatureConfiguration> {
 *
 *     private static final LoggedFeatureConfiguration CONFIGURATION = LoggedFeatureConfiguration.builder()
 *             .fieldName(LoggedField.REQUEST_ID, "trace-id")
 *             .requestIdHeader("X-Trace-ID")
 *             .build();
 *
 *     @Override
 *     public LoggedFeatureConfiguration getContext(Class<?> type) {
 *         return CONFIGURATION;
 *     }
 *
 * }
 * }</pre>
 * The resolver is asked for the class of the feature, and the default configuration applies when it resolves
 * none (see {@link #defaults()}). An application registering its providers explicitly passes the
 * configuration to {@link LoggedFeature#LoggedFeature(LoggedFeatureConfiguration)} instead.
 * <p>
 * It applies to every resource the feature logs, what varies from one to another being declared on the
 * resource itself, with {@link LoggedBody} and {@link LoggedMapping}.
 */
public final class LoggedFeatureConfiguration {

    private static final LoggedFeatureConfiguration DEFAULTS = builder().build();

    private final Map<LoggedField, String> fieldNames;
    private final String requestIdHeader;
    private final Function<ContainerRequestContext, @Nullable String> requestId;
    private final boolean requestIdReturned;
    private final BiPredicate<MappingType, String> sensitiveParameters;
    private final IntFunction<@Nullable Level> responseLevel;
    private final IntFunction<LoggedBodyCapture> bodyCapture;
    private final BiFunction<ContainerRequestContext, ResourceInfo, Map<String, String>> mdcEntries;

    private LoggedFeatureConfiguration(Builder builder) {
        this.fieldNames = unmodifiableMap(new EnumMap<>(builder.fieldNames));
        String header = builder.requestIdHeader;
        this.requestIdHeader = header;
        this.requestId = builder.requestId != null ? builder.requestId : request -> requestIdFromHeader(request, header);
        this.requestIdReturned = builder.requestIdReturned;
        this.sensitiveParameters = builder.sensitiveParameters;
        this.responseLevel = builder.responseLevel;
        this.bodyCapture = builder.bodyCapture;
        this.mdcEntries = builder.mdcEntries;
    }

    /**
     * Gets the default configuration, the one of a {@link LoggedFeature} the application declares none for.
     *
     * @return The configuration every setting of {@link Builder} documents the default of
     */
    public static LoggedFeatureConfiguration defaults() {
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
     * Indicates whether the given parameter is named the way callers conventionally name a credential -
     * {@code Authorization}, {@code Cookie} or {@code X-Api-Key} for a header, {@code access_token},
     * {@code password} or {@code client_secret} for a query parameter, among others, whatever their casing.
     * The default of {@link Builder#sensitiveParameters(BiPredicate)}, to compose with.
     * <p>
     * Path parameters are never reported: the application names them, not its callers.
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
     * Gets the level an exchange answered with the given status is logged at by default: {@link Level#ERROR}
     * for a server error, {@link Level#WARN} for a client error, {@link Level#INFO} otherwise. The default of
     * {@link Builder#responseLevel(IntFunction)}, and of {@link LoggedClientFeature.Builder#responseLevel}, to
     * compose with.
     * <p>
     * A client error is a warning rather than an error: on the server side, it says something about the caller
     * rather than the service, and on the client side, it points at a bug rather than an outage.
     *
     * @param status The status the exchange was answered with, {@code 0} when unknown
     * @return The level to log the exchange at
     */
    public static Level levelOf(int status) {
        if (status >= 500) {
            return Level.ERROR;
        } else if (status >= 400) {
            return Level.WARN;
        }
        return Level.INFO;
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
     *         (see {@link Sanitizer#requestIdOf(String)})
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
     * Gets the MDC entries the application describes the given request with.
     *
     * @param request  The context of the request received
     * @param resource The resource method matched by the request
     * @return The entries, by MDC key
     */
    Map<String, String> mdcEntriesOf(ContainerRequestContext request, ResourceInfo resource) {
        return mdcEntries.apply(request, resource);
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
     * Builder of {@link LoggedFeatureConfiguration}, starting from the default of every setting.
     */
    public static final class Builder {

        private final Map<LoggedField, String> fieldNames = new EnumMap<>(LoggedField.class);
        private String requestIdHeader = REQUEST_ID_HEADER;
        private @Nullable Function<ContainerRequestContext, @Nullable String> requestId;
        private boolean requestIdReturned = true;
        private BiPredicate<MappingType, String> sensitiveParameters = LoggedFeatureConfiguration::isCredential;
        private IntFunction<@Nullable Level> responseLevel = LoggedFeatureConfiguration::levelOf;
        private IntFunction<LoggedBodyCapture> bodyCapture = BoundedBodyCapture::new;
        private BiFunction<ContainerRequestContext, ResourceInfo, Map<String, String>> mdcEntries = (request, resource) -> Map.of();

        private Builder() {
            for (LoggedField field : LoggedField.values()) {
                fieldNames.put(field, field.getDefaultField());
            }
        }

        /**
         * Sets the name of the MDC entry of the given field, to align it with other applications or with the
         * schema the logs are shipped to. Defaults to {@link LoggedField#getDefaultField()}.
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
         * Leaves the given field out of MDC. The lines logging a request still show its method, URI, status and
         * duration, and a request identifier left out is no longer returned to the caller.
         *
         * @param field The field to leave out
         * @return This builder
         */
        public Builder withoutField(LoggedField field) {
            fieldNames.remove(requireNonNull(field, "The field is required"));
            return this;
        }

        /**
         * Sets the header the identifier of a request is read from, and returned to the caller in (see
         * {@link #withoutReturnedRequestId()}). Defaults to {@value LoggedFeature#REQUEST_ID_HEADER}.
         * <p>
         * The identifier is sanitized and truncated to 128 characters, and a random UUID is generated for a
         * request without one. It comes from the client all the same: a correlation hint, never an authenticated
         * value. See {@link #requestId(Function)} to generate it server-side instead.
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
         * Sets how the identifier of a request is obtained, for example to always generate it server-side when
         * the callers are untrusted:
         * <pre>{@code
         * .requestId(request -> UUID.randomUUID().toString())
         * }</pre>
         * The identifier is sanitized and truncated like one read from the header, and a request the strategy
         * obtains none for - {@code null}, blank, or failing - gets a random UUID. Defaults to reading the
         * {@link #requestIdHeader(String) request identifier header}.
         *
         * @param strategy The strategy getting the identifier of the given request
         * @return This builder
         */
        public Builder requestId(Function<ContainerRequestContext, @Nullable String> strategy) {
            this.requestId = requireNonNull(strategy, "The request identifier strategy is required");
            return this;
        }

        /**
         * Stops returning the identifier a request was logged under to the caller, in the
         * {@link #requestIdHeader(String) request identifier header}, which is done by default unless the
         * response carries that header already: a caller quoting it points straight at the lines of its request.
         *
         * @return This builder
         */
        public Builder withoutReturnedRequestId() {
            this.requestIdReturned = false;
            return this;
        }

        /**
         * Sets which parameters have their value kept out of the logs: an automatic {@link LoggedMapping} skips
         * them, and {@link LoggedField#REQUEST_PARAMETERS} masks the value of a query parameter, keeping its
         * name. An explicit mapping naming a parameter is a deliberate decision, left alone.
         * <p>
         * Defaults to {@link #isCredential(MappingType, String)}, to compose with for an application knowing
         * its own, for example to also mask a query parameter carrying a signed URL token:
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
         * Sets the level a request is logged at once answered, given its status, for example to leave an
         * expected {@code 404} at {@code INFO}. A status the function returns {@code null} for is logged at its
         * default level. Defaults to {@link #levelOf(int)}, to compose with.
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
         * limit, for example spilling large ones to a temporary file; {@link LoggedBodyFilter} transforms what
         * was captured. Defaults to capturing them in memory ({@link BoundedBodyCapture}).
         *
         * @param factory The creation of the capture of a body keeping at most the given number of bytes
         * @return This builder
         */
        public Builder bodyCapture(IntFunction<LoggedBodyCapture> factory) {
            this.bodyCapture = requireNonNull(factory, "The body capture factory is required");
            return this;
        }

        /**
         * Sets the MDC entries of the application describing a request, put along with the fields before any
         * line logging it, and removed with them once it is done. For example, to put the user an
         * authentication filter identified:
         * <pre>{@code
         * .mdcEntries((request, resource) -> Optional.ofNullable(request.getSecurityContext().getUserPrincipal())
         *         .map(user -> Map.of("user-id", user.getName()))
         *         .orElse(Map.of()))
         * }</pre>
         * Values are sanitized as the fields are, and ignored when blank. A function failing costs the request
         * these entries alone. Defaults to none.
         *
         * @param entries The entries describing the given request, by MDC key, given the resource method matched
         * @return This builder
         */
        public Builder mdcEntries(BiFunction<ContainerRequestContext, ResourceInfo, Map<String, String>> entries) {
            this.mdcEntries = requireNonNull(entries, "The MDC entries function is required");
            return this;
        }

        /**
         * Builds the configuration set on this builder.
         *
         * @return The configuration built
         * @throws IllegalStateException if two fields are given the same name, the entry of one overwriting the
         *                               entry of the other
         */
        public LoggedFeatureConfiguration build() {
            Map<String, LoggedField> fieldsByName = new HashMap<>();
            fieldNames.forEach((field, name) -> {
                LoggedField other = fieldsByName.putIfAbsent(name, field);
                if (other != null) {
                    throw new IllegalStateException("The fields " + other + " and " + field + " are both named " + name);
                }
            });
            return new LoggedFeatureConfiguration(this);
        }

    }

}
