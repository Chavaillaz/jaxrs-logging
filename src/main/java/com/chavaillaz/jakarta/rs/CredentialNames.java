package com.chavaillaz.jakarta.rs;

import java.util.Locale;
import java.util.Set;

/**
 * The header and query parameter names that carry a credential often enough to be kept out of the logs
 * without anyone having to ask.
 * <p>
 * This is a denylist, and a denylist of names nobody controls at that: it catches what callers
 * conventionally call their secrets, not what an application's own API happens to name them. It is a
 * floor, not a guarantee - see {@link LoggedFilter#isSensitive(LoggedMapping.MappingType, String)} to
 * raise it for a particular application.
 * <p>
 * Kept apart from {@link LoggedFilter} because it is data, not behaviour: which names are secrets is a
 * question about the world (what OAuth, HTTP authentication and the average internal API call things),
 * answered the same way regardless of what the provider does with the answer, and it is the part of this
 * library most likely to be read on its own by someone asking "is my token in the logs?".
 * <p>
 * Every name here is lower case and every lookup lower-cases what it is given, which is why the sets are
 * only reachable through {@link #isHeader(String)} and {@link #isQueryParameter(String)}: HTTP header
 * names are case-insensitive, and a query parameter carrying a secret does not become safe by being
 * spelled {@code Access_Token}. Leaving the sets directly readable invited a caller to test membership
 * without normalizing first, which is a check that passes every test written against lower-case names
 * and misses the real request.
 */
public final class CredentialNames {

    /**
     * Headers whose value must never be copied into MDC by an automatic {@link LoggedMapping}: such a
     * mapping is a blanket "map everything the client sent" instruction, which is exactly how bearer
     * tokens, session cookies and API keys end up permanently stored in a log aggregator by an
     * application that never intended to log them.
     * <p>
     * Only automatic mappings consult this. An explicit mapping naming a header is a deliberate decision
     * by the developer and is left alone.
     */
    private static final Set<String> HEADERS = Set.of(
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
     * {@link #HEADERS} and with more urgency: unlike a header, a query parameter is logged by default,
     * without anything having to be configured, as part of {@link LoggedField#REQUEST_PARAMETERS}.
     * <p>
     * Passing a credential in a query string is bad practice and well known as such, yet it is exactly
     * what OAuth's implicit and authorization-code-in-URL flows, presigned URLs and countless internal
     * APIs do, so an application has no say in whether its callers do it. Reaching a value here is not a
     * decision by the developer the way an explicitly named {@link LoggedMapping} is, so, unlike a
     * header, this applies whether or not any mapping is involved.
     * <p>
     * Names too commonly used for ordinary things are deliberately absent - {@code code} would mask an
     * OAuth authorization code and a country code alike - and are left to applications that know their
     * own callers.
     */
    private static final Set<String> QUERY_PARAMETERS = Set.of(
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

    private CredentialNames() {
        // Utility class
    }

    /**
     * Indicates whether a header of the given name conventionally carries a credential.
     *
     * @param name The header name, in any casing
     * @return {@code true} if the header carries a credential, {@code false} otherwise
     */
    public static boolean isHeader(String name) {
        return name != null && HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Indicates whether a query parameter of the given name conventionally carries a credential.
     *
     * @param name The query parameter name, in any casing
     * @return {@code true} if the parameter carries a credential, {@code false} otherwise
     */
    public static boolean isQueryParameter(String name) {
        return name != null && QUERY_PARAMETERS.contains(name.toLowerCase(Locale.ROOT));
    }

}
