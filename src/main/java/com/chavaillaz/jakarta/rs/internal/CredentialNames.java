package com.chavaillaz.jakarta.rs.internal;

import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.rs.LoggedFeatureConfiguration;
import com.chavaillaz.jakarta.rs.LoggedField;
import com.chavaillaz.jakarta.rs.LoggedMapping;

/**
 * The names of the headers and query parameters carrying a credential often enough to be kept out of the logs
 * by default: what callers conventionally name their secrets, a floor rather than a guarantee (see
 * {@link LoggedFeatureConfiguration.Builder#sensitiveParameters(java.util.function.BiPredicate)}). Names are
 * compared whatever their casing.
 */
public final class CredentialNames {

    /**
     * Headers an automatic {@link LoggedMapping} leaves out, as it would otherwise copy bearer tokens, session
     * cookies and API keys into the logs. An explicit mapping naming one is a deliberate decision, left alone.
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
     * Query parameters whose value is masked in {@link LoggedField#REQUEST_PARAMETERS}, logged by default, and
     * left out of automatic mappings: OAuth flows, presigned URLs and many APIs pass credentials in the query
     * string. Names too common for other uses, such as {@code code}, are left to the application.
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
