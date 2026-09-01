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
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static jakarta.ws.rs.RuntimeType.SERVER;
import static java.lang.String.join;
import static java.lang.String.valueOf;
import static java.lang.System.nanoTime;
import static java.util.Collections.synchronizedList;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.chavaillaz.jakarta.rs.LoggedBody.Direction;
import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;
import com.chavaillaz.jakarta.rs.LoggedResolver.BodyConfiguration;
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
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

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
 * <p>
 * That low priority is the right one for the filters but the wrong one for capturing bodies, as it places
 * this provider's interceptors outside any entity coder and therefore in front of the compressed bytes
 * rather than the entity itself. Capture is consequently delegated to {@link LoggedBodyInterceptor},
 * which runs after the coder and hands what it captures back here; see that class for the details.
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
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]");

    /**
     * Headers whose value must never be copied into MDC by an automatic {@link LoggedMapping}, as they
     * carry credentials: an automatic mapping is a blanket "map everything the client sent" instruction,
     * which is exactly how bearer tokens, session cookies and API keys end up permanently stored in a
     * log aggregator by an application that never intended to log them.
     * <p>
     * Only applies to {@link LoggedMapping#auto()}: an explicit mapping naming a header is a deliberate
     * decision by the developer and is left alone. Compared in lower case, see {@link #isSensitive}.
     */
    protected static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "www-authenticate",
            "proxy-authenticate",
            "cookie",
            "set-cookie",
            "x-api-key",
            "api-key",
            "x-auth-token",
            "x-access-token",
            "x-csrf-token",
            "x-xsrf-token");

    /**
     * Query parameters whose value must never be written to the logs, for the same reason as
     * {@link #SENSITIVE_HEADERS}, and with more urgency: unlike a header, a query parameter is logged
     * by default, without anything having to be configured, as part of {@link LoggedField#REQUEST_PARAMETERS}.
     * <p>
     * Passing a credential in a query string is bad practice and well known as such, yet it is exactly
     * what OAuth's implicit and authorization-code-in-URL flows, presigned URLs and countless internal
     * APIs do, so an application has no say in whether its callers do it. Reaching a value here is not a
     * decision by the developer the way an explicitly named {@link LoggedMapping} is, so, unlike a header,
     * this applies whether or not any mapping is involved: the value is replaced by
     * {@link MaskingBodyFilter#DEFAULT_MASK} while the parameter name stays visible, which is the part
     * that is useful for troubleshooting anyway.
     * <p>
     * Compared in lower case, see {@link #isSensitive}. Override that method to add whatever else an
     * application's callers put in a query string ({@code code} for an OAuth authorization code, a
     * signed-URL token, ...), left out here as they are too commonly ordinary parameter names to mask
     * for everyone by default.
     */
    protected static final Set<String> SENSITIVE_PARAMETERS = Set.of(
            "password",
            "passwd",
            "pwd",
            "secret",
            "client_secret",
            "token",
            "access_token",
            "refresh_token",
            "id_token",
            "api_key",
            "apikey",
            "auth",
            "authorization",
            "signature");

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
     * Name of the property stored in container context to retrieve the response body once it has been
     * written, for the same reason as {@link #REQUEST_BODY_PROPERTY}.
     */
    protected static final String RESPONSE_BODY_PROPERTY = LoggedFilter.class.getName() + ".responseBody";

    /**
     * Name of the property stored in container context to hand the instance handling the current request
     * to {@link LoggedBodyInterceptor}, which captures the bodies from a later position in the
     * interceptor chain but delegates every decision about them back to this provider.
     * <p>
     * The instance is passed through the request rather than injected there, so the capture is handed
     * back to the exact instance - a subclass, possibly one of several registered, with its own
     * {@code createBodyCapture} or MDC field names - that is handling this particular request.
     */
    protected static final String PROVIDER_PROPERTY = LoggedFilter.class.getName() + ".provider";

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
     * thread's MDC, not from the thread that actually set them - which is why {@link #filter(ContainerRequestContext)}
     * also sweeps stale entries at the start of every request (see {@link #resetMdc()} and
     * {@link #threadMdcKeys}).
     * <p>
     * The list itself is synchronized for that same reason: the thread completing the request is not
     * necessarily the one that started it, so entries can be added and closed from different threads.
     */
    protected static final String MDC_CLOSEABLES_PROPERTY = LoggedFilter.class.getName() + ".mdcCloseables";

    /**
     * Name of the property stored in container context to hold the body logging configuration resolved
     * for the current request, so the seven or so callbacks asking for it over the life of a request
     * share one resolution instead of repeating it.
     * <p>
     * The resolver already caches per resource method, so what this avoids is not the annotation
     * reflection but everything around it: two calls into {@link #resourceInfo} - a request-scoped
     * object the container usually hands out as a proxy resolving through a thread-local - plus a key
     * allocation and a map lookup, on the hot path of every request the application serves.
     * <p>
     * Keeping it on the request rather than on the thread also means the configuration is still the one
     * belonging to this request when a later callback runs somewhere the container did not bind a
     * resource to, which is where reading {@link #resourceInfo} again would quietly resolve to nothing
     * and turn body logging off for a request that had asked for it.
     */
    protected static final String BODY_CONFIGURATION_PROPERTY = LoggedFilter.class.getName() + ".bodyConfiguration";

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
     * MDC keys this provider has put on the current thread, whichever request they belong to, so
     * {@link #resetMdc()} can sweep every one of them and not just the fixed {@link #mdcFields}.
     * <p>
     * The closeables tracked per request (see {@link #MDC_CLOSEABLES_PROPERTY}) cannot remove an entry
     * from a thread other than the one closing them, so a request completed elsewhere - a
     * {@code @Suspended} response resumed from a worker, a reactive resource method, a container never
     * reaching this provider's completion callbacks at all - leaves its entries on the thread that set
     * them. Sweeping {@link #mdcFields} alone was enough for {@code request-id} and its siblings, whose
     * names are known up front, but not for the keys whose names are only known at runtime: an automatic
     * {@link LoggedMapping} derives them from the parameters the client sent, and a subclass can put
     * anything it likes through {@link #putMdc(String, String)}. Those stayed attached to the (pooled)
     * thread and mislabelled every log line of the unrelated requests it went on to serve, with no
     * request ever overwriting them because the next client sends different headers.
     * <p>
     * Recorded per thread rather than per request precisely because it is the thread, not the request,
     * that outlives the leak. Removed rather than cleared once swept, so a thread pool outliving the
     * application does not keep a now-useless entry alive in each of its threads.
     */
    private static final ThreadLocal<Set<String>> threadMdcKeys = new ThreadLocal<>();

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
            closeables = synchronizedList(new ArrayList<>());
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
     * @param value The value to be associated with the given key, ignored if {@code null} or blank
     */
    protected void putMdc(String key, String value) {
        // Blank values are dropped rather than stored as an empty entry: an always-present, always-empty
        // field (a request with no query parameter, a header sent with no value) is pure noise in every
        // structured log line of the application, and is indistinguishable from a legitimately empty one
        if (isNotBlank(value)) {
            trackMdcKey(key);
            getMdcCloseables().add(MDC.putCloseable(key, value));
        }
    }

    /**
     * Records the given key as having been put in MDC on the current thread, see {@link #threadMdcKeys}.
     *
     * @param key The MDC key just put on the current thread
     */
    private static void trackMdcKey(String key) {
        Set<String> keys = threadMdcKeys.get();
        if (keys == null) {
            keys = new HashSet<>();
            threadMdcKeys.set(keys);
        }
        keys.add(key);
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
     * Indicates whether the value of the given parameter must be kept out of the logs.
     * <p>
     * Two things honour this: an automatic {@link LoggedMapping}, which skips the parameter entirely
     * rather than copying it into MDC, and {@link #getQueryParameters(ContainerRequestContext)}, which
     * masks the value while keeping the parameter name. An explicit mapping naming a parameter is a
     * deliberate decision by the developer and is left alone by both.
     * <p>
     * Override to extend (or restrict) the defaults, for example to also mask a query parameter carrying
     * a signed URL token:
     * <pre>{@code
     * @Override
     * protected boolean isSensitive(MappingType type, String name) {
     *     return super.isSensitive(type, name)
     *             || (type == QUERY && "url-signature".equalsIgnoreCase(name));
     * }
     * }</pre>
     *
     * @param type The type of parameter being logged
     * @param name The name of the parameter being logged
     * @return {@code true} if the value must be kept out of the logs, {@code false} otherwise
     */
    protected boolean isSensitive(MappingType type, String name) {
        return switch (type) {
            case HEADER -> SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
            case QUERY -> SENSITIVE_PARAMETERS.contains(name.toLowerCase(Locale.ROOT));
            // Path parameter names are chosen by the application itself, not by whoever calls it, so
            // there is no equivalent list of names that "just happen" to carry a credential
            case PATH -> false;
        };
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
                    .filter(entry -> !isSensitive(mapping.type(), entry.getKey()))
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
     * <p>
     * Note that the identifier is taken from the client as-is (beyond truncation and
     * {@link #sanitize(String)}): it is a correlation hint, never an authenticated value, so nothing
     * downstream should treat two requests sharing one as necessarily related. Override this method to
     * always generate the identifier server-side when the caller is untrusted.
     *
     * @param requestContext The context of the request received
     * @return The request identifier
     */
    protected String getRequestId(ContainerRequestContext requestContext) {
        return of(requestContext)
                .map(ContainerRequestContext::getHeaders)
                .map(headers -> headers.getFirst(REQUEST_ID_HEADER))
                .filter(org.apache.commons.lang3.StringUtils::isNotBlank)
                .map(value -> value.length() > REQUEST_ID_MAX_LENGTH ? value.substring(0, REQUEST_ID_MAX_LENGTH) : value)
                // orElseGet (not orElse) so a UUID, which is comparatively expensive to generate
                // (backed by SecureRandom), is only computed when the header is actually absent
                .orElseGet(() -> randomUUID().toString());
    }

    /**
     * Runs the given logging action, swallowing anything it throws.
     * <p>
     * Every callback of this provider does its work in a {@code finally} block, so that a request is
     * logged (and its MDC cleaned up) even when the exchange itself failed. That placement makes an
     * exception thrown while logging strictly worse than useless: it <em>replaces</em> the application
     * exception on its way out, so a bug in a {@link LoggedBodyFilter}, an appender that ran out of disk
     * or a container returning an unexpected {@code null} does not just lose a log line, it turns the
     * real failure into an unrelated one - or turns a perfectly good response into a 500.
     * <p>
     * Observability must never be the reason a request fails: whatever goes wrong here is reported on
     * this provider's own logger and goes no further.
     *
     * @param action The logging action to run
     */
    protected void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            try {
                log.error("Unable to log the request or response, the exchange itself is left unaffected", e);
            } catch (Exception ignored) {
                // Nothing left to report it with: reporting must not be the thing that breaks the request
            }
        }
    }

    /**
     * Removes any MDC entry owned by this provider still present on the current thread.
     * <p>
     * Called at the very start of every request as a safety net, not as the normal cleanup path (which is
     * {@link #cleanupMdc()}): MDC is thread-local and request threads are pooled, so an entry that could
     * not be removed at the end of a previous request - because it completed on another thread, or because
     * the container never reached this provider's completion callbacks (an entity whose
     * {@code MessageBodyWriter} failed to be selected, for instance) - would otherwise stay attached to
     * this thread and silently mislabel every log line of the unrelated request now running on it.
     * <p>
     * Covers both the fixed {@link #mdcFields}, whose names are known up front, and every other key this
     * provider put on this thread (see {@link #threadMdcKeys}), such as those an automatic
     * {@link LoggedMapping} derives from what the client sent.
     */
    protected void resetMdc() {
        mdcFields.values().forEach(MDC::remove);
        Set<String> keys = threadMdcKeys.get();
        if (keys != null) {
            keys.forEach(MDC::remove);
            threadMdcKeys.remove();
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        resetMdc();
        // Set through the injected context rather than the argument (the very same object in a
        // container) so every property this provider writes is written where all of them are read back:
        // the interceptor callbacks and logResponse only ever see the injected one
        this.requestContext.setProperty(REQUEST_TIME_PROPERTY, nanoTime());
        this.requestContext.setProperty(PROVIDER_PROPERTY, this);
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
     * Renders the query parameters of the given request as a single, deterministically ordered string,
     * masking the value of those {@link #isSensitive(MappingType, String)} reports as credential-carrying.
     * <p>
     * The parameter name is kept even when its value is masked, as the name is what is useful for
     * troubleshooting (knowing an {@code access_token} was supplied at all) and is not itself the secret.
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
                .map(entry -> entry.getKey() + "=" + (isSensitive(QUERY, entry.getKey())
                        ? MaskingBodyFilter.DEFAULT_MASK
                        : join(",", entry.getValue())))
                .collect(joining("&"));
    }

    /**
     * Indicates whether anything this provider writes would actually reach an appender.
     * <p>
     * Used to skip body capture entirely when it would be thrown away: buffering (and filtering, and
     * decoding) every request and response body of an application whose logger is configured above
     * {@code INFO} is pure overhead, and is exactly the kind of cost that is invisible until it shows
     * up as allocation pressure in production.
     * <p>
     * Checks {@code INFO}, the lowest level this provider writes at, and not the level the completion of
     * this particular request will end up being logged at ({@link #getResponseLevel(String)}): whether a
     * request failed is only known once it has been answered, long after the decision to capture its body
     * had to be made. An application configured above {@code INFO} therefore gets its failures logged at
     * {@code WARN}/{@code ERROR} but without bodies, which is the deliberate trade: the alternative is
     * buffering every body of every request in case it turns out to fail.
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
        try {
            return context.proceed();
        } finally {
            // Logs whatever was captured even if reading the entity failed (e.g. malformed payload),
            // so a deserialization error does not leave the request entirely unlogged
            safely(() -> {
                String body = (String) requestContext.getProperty(REQUEST_BODY_PROPERTY);
                if (isNotBlank(body) && getBodyConfiguration(REQUEST).logs(LOG)) {
                    logRequest(body);
                }
            });
        }
    }

    /**
     * Captures the request body while the entity is being read, storing it as
     * {@link #REQUEST_BODY_PROPERTY} for {@link #aroundReadFrom(ReaderInterceptorContext)} and
     * {@link #logResponse(String)} to log.
     * <p>
     * Called by {@link LoggedBodyInterceptor} rather than from this provider's own interceptor position,
     * so what is captured is the entity's own representation rather than the transfer-encoded bytes on
     * the wire (see that class for why the two positions differ).
     *
     * @param context The context of the entity being read
     * @return The entity read
     * @throws IOException              if an IO error arises while reading the entity
     * @throws WebApplicationException  if the entity cannot be read
     */
    protected Object captureRequestBody(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        LoggedBodyConfiguration configuration = getBodyConfiguration(REQUEST);
        if (!configuration.isActive() || !isLoggingEnabled()) {
            return context.proceed();
        }

        LoggedBodyCapture capture = createBodyCapture(configuration.limit());
        context.setInputStream(new TeeInputStream(context.getInputStream(), capture.sink()));
        try {
            return context.proceed();
        } finally {
            safely(() -> requestContext.setProperty(REQUEST_BODY_PROPERTY,
                    capture.content(configuration.filters(), context.getMediaType())));
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
        try {
            context.proceed();
        } finally {
            // Always log and clean up MDC, even if writing the response body fails (e.g. client
            // disconnection, serialization error), to avoid leaking context fields onto a pooled thread
            // and to avoid leaving the response entirely unlogged
            safely(() -> {
                try {
                    LoggedBodyConfiguration configuration = getBodyConfiguration(RESPONSE);
                    String body = (String) requestContext.getProperty(RESPONSE_BODY_PROPERTY);
                    if (configuration.logs(LoggedBody.LogType.MDC)) {
                        putMdc(RESPONSE_BODY, body);
                    }
                    logResponse(configuration.logs(LOG) ? requireNonNullElse(body, EMPTY) : EMPTY);
                } catch (Exception e) {
                    // Completion is what cleans MDC up, so it must still happen when assembling the log
                    // line above failed, or the fields would stay behind on this (pooled) thread
                    logResponse(EMPTY);
                    throw e;
                }
            });
        }
    }

    /**
     * Captures the response body while the entity is being written, storing it as
     * {@link #RESPONSE_BODY_PROPERTY} for {@link #aroundWriteTo(WriterInterceptorContext)} to log.
     * <p>
     * Called by {@link LoggedBodyInterceptor}, for the same reason as
     * {@link #captureRequestBody(ReaderInterceptorContext)}.
     *
     * @param context The context of the entity being written
     * @throws IOException              if an IO error arises while writing the entity
     * @throws WebApplicationException  if the entity cannot be written
     */
    protected void captureResponseBody(WriterInterceptorContext context) throws IOException, WebApplicationException {
        LoggedBodyConfiguration configuration = getBodyConfiguration(RESPONSE);
        if (!configuration.isActive() || !isLoggingEnabled()) {
            context.proceed();
            return;
        }

        LoggedBodyCapture capture = createBodyCapture(configuration.limit());
        context.setOutputStream(new TeeOutputStream(context.getOutputStream(), capture.sink()));
        try {
            context.proceed();
        } finally {
            safely(() -> requestContext.setProperty(RESPONSE_BODY_PROPERTY,
                    capture.content(configuration.filters(), context.getMediaType())));
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
     * The duration is measured here rather than when the response filter runs, so it covers serializing
     * and writing the entity too: a response whose body takes 200ms to render used to be reported as
     * having been processed in the handful of milliseconds preceding it.
     * <p>
     * Note that the response status must have been stored in MDC before calling this method.
     *
     * @param responseBody The response body to be logged
     */
    protected void logResponse(String responseBody) {
        if (Boolean.TRUE.equals(requestContext.getProperty(COMPLETED_PROPERTY))) {
            return;
        }
        requestContext.setProperty(COMPLETED_PROPERTY, Boolean.TRUE);

        try {
            long requestStartTime = Optional.ofNullable(requestContext.getProperty(REQUEST_TIME_PROPERTY))
                    .map(Number.class::cast)
                    .map(Number::longValue)
                    .orElseGet(System::nanoTime);
            putMdc(DURATION, valueOf((nanoTime() - requestStartTime) / 1_000_000));

            if (getBodyConfiguration(REQUEST).logs(LoggedBody.LogType.MDC)) {
                putMdc(REQUEST_BODY, (String) requestContext.getProperty(REQUEST_BODY_PROPERTY));
            }

            String status = getMdc(RESPONSE_STATUS);
            log.atLevel(getResponseLevel(status))
                    .log("Processed {} {} with status {} in {}ms{}{}",
                            getMdc(REQUEST_METHOD),
                            getMdc(REQUEST_URI),
                            status,
                            getMdc(DURATION),
                            isNotBlank(responseBody) ? LF : EMPTY,
                            responseBody);

        } finally {
            cleanupMdc();
        }
    }

    /**
     * Gets the level at which the completion of a request answered with the given status is logged.
     * <p>
     * A server error logged at the same level as a successful call is a log line nobody is alerted on:
     * the one place that knows a request failed is the library writing the line, and leaving every
     * completion at {@code INFO} pushes that knowledge into a message-parsing rule in whatever consumes
     * the logs. Server errors are therefore logged at {@code ERROR}, client errors at {@code WARN}, and
     * everything else at {@code INFO}.
     * <p>
     * Client errors are deliberately not errors: a {@code 404} or a {@code 400} is the application
     * working as designed and says something about the caller, not about the service, so alerting on it
     * would page someone for somebody else's typo. {@code WARN} keeps them visible without that.
     * <p>
     * Override to fit an application's own conventions, for example to leave an expected {@code 404} at
     * {@code INFO}, or to raise a specific status the application treats as an incident.
     *
     * @param status The response status as stored in MDC, possibly {@code null} if it was never resolved
     * @return The level to log the completion of the request at
     */
    protected Level getResponseLevel(String status) {
        // Parsed rather than taken from the response context, so the level still matches the status that
        // was actually logged when completion happens somewhere the response context is not at hand
        int code = NumberUtils.toInt(status);
        if (code >= 500) {
            return Level.ERROR;
        } else if (code >= 400) {
            return Level.WARN;
        }
        return Level.INFO;
    }

    /**
     * Gets the body logging configuration for the given target (request or response) of the resource
     * method matched by the current request, delegating resolution and caching to {@link #resolver} the
     * first time it is asked for and reusing that result for the rest of the request afterwards (see
     * {@link #BODY_CONFIGURATION_PROPERTY}).
     *
     * @param target The target for which to find the body logging configuration
     * @return The body logging configuration, never {@code null}
     */
    protected LoggedBodyConfiguration getBodyConfiguration(Direction target) {
        BodyConfiguration configuration = (BodyConfiguration) requestContext.getProperty(BODY_CONFIGURATION_PROPERTY);
        if (configuration == null) {
            configuration = resolver.getBodyConfiguration(resourceInfo);
            requestContext.setProperty(BODY_CONFIGURATION_PROPERTY, configuration);
        }
        return configuration.of(target);
    }

    /**
     * Closes every {@link MDC.MDCCloseable} obtained through {@link #putMdc(String, String)} for the
     * current request (see {@link #MDC_CLOSEABLES_PROPERTY}), which covers every entry put by this
     * provider itself as well as any subclass following the same convention.
     * <p>
     * Also sweeps, through {@link #resetMdc()}, the fixed set of fields in {@link #mdcFields} as a safety
     * net, in case a subclass still puts one of those directly through {@link MDC#put(String, String)}
     * (as opposed to {@link #putMdc(LoggedField, String)}) after registering it there, along with every
     * other key this provider put on the completing thread.
     */
    protected void cleanupMdc() {
        resetMdc();
        List<MDC.MDCCloseable> closeables = getMdcCloseables();
        synchronized (closeables) {
            closeables.forEach(MDC.MDCCloseable::close);
            closeables.clear();
        }
    }

}
