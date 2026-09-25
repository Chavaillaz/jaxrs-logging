package com.chavaillaz.jakarta.rs.internal;

import java.util.Locale;
import java.util.Set;

import com.chavaillaz.jakarta.rs.LoggedField;
import com.chavaillaz.jakarta.rs.LoggedFilterConfiguration;
import com.chavaillaz.jakarta.rs.LoggedMapping;
import org.jspecify.annotations.Nullable;

/**
 * The header and query parameter names that carry a credential often enough to be kept out of the logs
 * without anyone having to ask.
 * <p>
 * This is a denylist, and a denylist of names nobody controls at that: it catches what callers
 * conventionally call their secrets, not what an application's own API happens to name them. It is a
 * floor, not a guarantee - see
 * {@link LoggedFilterConfiguration.Builder#sensitiveParameters(java.util.function.BiPredicate)} to raise it for a
 * particular application.
 * <p>
 * Every name here is lower case and every lookup lower-cases what it is given, which is why the sets are
 * only reachable through {@link #isHeader(String)} and {@link #isQueryParameter(String)}: HTTP header
 * names are case-insensitive, and a query parameter carrying a secret does not become safe by being
 * spelled {@code Access_Token}.
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
            "x-xsrf-token",
            // The API keys and tokens of widely used platforms: AWS temporary credentials, Google APIs,
            // Azure API Management and Functions, GitLab, HashiCorp Vault
            "x-amz-security-token",
            "x-goog-api-key",
            "ocp-apim-subscription-key",
            "x-functions-key",
            "private-token",
            "x-vault-token");

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
            "signature",
            // What grants access to whatever a presigned URL points at: the signature of an AWS, Google
            // Cloud Storage or Azure one, and the session token of AWS temporary credentials
            "x-amz-signature",
            "x-amz-security-token",
            "x-goog-signature",
            "sig",
            "private_token");

    private CredentialNames() {
        // Utility class
    }

    /**
     * Indicates whether a header of the given name conventionally carries a credential.
     *
     * @param name The header name, in any casing, possibly {@code null}
     * @return {@code true} if the header carries a credential, {@code false} otherwise
     */
    public static boolean isHeader(@Nullable String name) {
        return name != null && HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Indicates whether a query parameter of the given name conventionally carries a credential.
     *
     * @param name The query parameter name, in any casing, possibly {@code null}
     * @return {@code true} if the parameter carries a credential, {@code false} otherwise
     */
    public static boolean isQueryParameter(@Nullable String name) {
        return name != null && QUERY_PARAMETERS.contains(name.toLowerCase(Locale.ROOT));
    }

}
