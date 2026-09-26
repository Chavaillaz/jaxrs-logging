package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.internal.CredentialNames.isHeader;
import static com.chavaillaz.jakarta.rs.internal.CredentialNames.isQueryParameter;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Credential names")
class CredentialNamesTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Authorization", "cookie", "X-Api-Key", "X-Amz-Security-Token", "x-goog-api-key",
            "Ocp-Apim-Subscription-Key", "x-functions-key", "Private-Token", "X-Vault-Token"})
    @DisplayName("Check a header conventionally carrying a credential is recognized whatever its casing")
    void checkCredentialHeaderRecognized(String name) {
        assertTrue(isHeader(name));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"access_token", "Password", "client_secret", "X-Amz-Signature",
            "X-Amz-Security-Token", "X-Goog-Signature", "sig", "private_token"})
    @DisplayName("Check a query parameter conventionally carrying a credential is recognized whatever its casing")
    void checkCredentialQueryParameterRecognized(String name) {
        // The signature and session token of a presigned URL are what grants access to what it points at
        assertTrue(isQueryParameter(name));
    }

    @Test
    @DisplayName("Check ordinary names are left alone, and a missing one is not mistaken for a credential")
    void checkOrdinaryNamesNotRecognized() {
        assertFalse(isHeader("User-Agent"));
        assertFalse(isHeader(null));
        assertFalse(isQueryParameter("topic"));
        assertFalse(isQueryParameter("code"));
        assertFalse(isQueryParameter(null));
    }

}
