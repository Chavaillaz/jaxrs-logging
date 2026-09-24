package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_URI;
import static com.chavaillaz.jakarta.rs.LoggedFilterConfiguration.isCredential;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.PATH;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.resteasy.core.interception.jaxrs.PreMatchContainerRequestContext;
import org.jboss.resteasy.mock.MockHttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.Level;

@DisplayName("Logged filter configuration")
class LoggedFilterConfigurationTest {

    static ContainerRequestContext request(String header, String value) throws Exception {
        return new PreMatchContainerRequestContext(MockHttpRequest.create("GET", "/articles").header(header, value));
    }

    @Test
    @DisplayName("Check the default configuration names every field after its default name")
    void checkDefaultFieldNames() {
        LoggedFilterConfiguration configuration = LoggedFilterConfiguration.defaults();

        Arrays.stream(LoggedField.values())
                .forEach(field -> assertEquals(field.getDefaultField(), configuration.fieldName(field)));
        assertSame(configuration, LoggedFilterConfiguration.defaults());
    }

    @Test
    @DisplayName("Check the default configuration reads and returns the identifier in the X-Request-ID header")
    void checkDefaultRequestId() throws Exception {
        LoggedFilterConfiguration configuration = LoggedFilterConfiguration.defaults();

        assertEquals("abc-123", configuration.requestIdOf(request(LoggedFilter.REQUEST_ID_HEADER, "abc-123")));
        assertDoesNotThrow(() -> UUID.fromString(configuration.requestIdOf(request("X-Other", "abc-123"))));
        assertEquals(LoggedFilter.REQUEST_ID_HEADER, configuration.returnedRequestIdHeader());
    }

    @Test
    @DisplayName("Check the default configuration logs at a level following the status and captures in memory")
    void checkDefaultLevelsAndCapture() {
        LoggedFilterConfiguration configuration = LoggedFilterConfiguration.defaults();

        assertEquals(Level.INFO, configuration.responseLevel(200));
        assertEquals(Level.WARN, configuration.responseLevel(404));
        assertEquals(Level.ERROR, configuration.responseLevel(503));
        assertInstanceOf(BoundedLoggedBodyCapture.class, configuration.createBodyCapture(10));
    }

    @Test
    @DisplayName("Check the default sensitive parameters are the credentials callers conventionally send")
    void checkDefaultSensitiveParameters() {
        LoggedFilterConfiguration configuration = LoggedFilterConfiguration.defaults();

        assertTrue(configuration.isSensitive(HEADER, "Authorization"));
        assertTrue(configuration.isSensitive(QUERY, "access_token"));
        assertFalse(configuration.isSensitive(QUERY, "topic"));
        // Named by the application itself, not by whoever calls it
        assertFalse(isCredential(PATH, "password"));
    }

    @Test
    @DisplayName("Check a field can be renamed or left out")
    void checkFieldRenamedOrLeftOut() {
        LoggedFilterConfiguration configuration = LoggedFilterConfiguration.builder()
                .fieldName(REQUEST_ID, "trace-id")
                .withoutField(REQUEST_URI)
                .build();

        assertEquals("trace-id", configuration.fieldName(REQUEST_ID));
        assertNull(configuration.fieldName(REQUEST_URI));
        assertFalse(configuration.fieldNames().containsKey(REQUEST_URI));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("Check a field cannot be given a blank name")
    void checkBlankFieldNameRejected(String name) {
        LoggedFilterConfiguration.Builder builder = LoggedFilterConfiguration.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.fieldName(REQUEST_ID, name));
        assertThrows(IllegalArgumentException.class, () -> builder.fieldName(REQUEST_ID, null));
        assertThrows(NullPointerException.class, () -> builder.fieldName(null, "request-id"));
    }

    @Test
    @DisplayName("Check two fields cannot share a name, as one entry would silently overwrite the other")
    void checkSharedFieldNameRejected() {
        LoggedFilterConfiguration.Builder builder = LoggedFilterConfiguration.builder()
                .fieldName(REQUEST_ID, REQUEST_URI.getDefaultField());

        IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(exception.getMessage().contains(REQUEST_URI.getDefaultField()));

        // Leaving the other one out frees its name
        assertDoesNotThrow(() -> builder.withoutField(REQUEST_URI).build());
    }

    @Test
    @DisplayName("Check the identifier is read from and returned in the header configured")
    void checkRequestIdHeader() throws Exception {
        LoggedFilterConfiguration configuration = LoggedFilterConfiguration.builder()
                .requestIdHeader("X-Case-ID")
                .build();

        assertEquals("case-42", configuration.requestIdOf(request("X-Case-ID", "case-42")));
        assertEquals("X-Case-ID", configuration.returnedRequestIdHeader());
        assertThrows(IllegalArgumentException.class, () -> LoggedFilterConfiguration.builder().requestIdHeader(" "));
    }

    @Test
    @DisplayName("Check a strategy of its own replaces reading the identifier from the header")
    void checkRequestIdStrategy() throws Exception {
        LoggedFilterConfiguration configuration = LoggedFilterConfiguration.builder()
                .requestId(request -> "server-side")
                .build();

        assertEquals("server-side", configuration.requestIdOf(request(LoggedFilter.REQUEST_ID_HEADER, "client-side")));
    }

    @Test
    @DisplayName("Check the identifier is not returned once configured not to be")
    void checkRequestIdNotReturned() {
        LoggedFilterConfiguration configuration = LoggedFilterConfiguration.builder()
                .withoutReturnedRequestId()
                .build();

        assertNull(configuration.returnedRequestIdHeader());
    }

    @Test
    @DisplayName("Check the strategies configured replace the default ones")
    void checkStrategiesReplaced() {
        LoggedFilterConfiguration configuration = LoggedFilterConfiguration.builder()
                .sensitiveParameters((type, name) -> isCredential(type, name) || "url-signature".equals(name))
                .responseLevel(status -> Level.DEBUG)
                .bodyCapture(limit -> new BoundedLoggedBodyCapture(1))
                .build();

        assertTrue(configuration.isSensitive(QUERY, "url-signature"));
        assertTrue(configuration.isSensitive(QUERY, "access_token"));
        assertEquals(Level.DEBUG, configuration.responseLevel(500));
        assertInstanceOf(BoundedLoggedBodyCapture.class, configuration.createBodyCapture(-1));
    }

    @Test
    @DisplayName("Check a missing strategy is rejected when set rather than when a request needs it")
    void checkNullStrategiesRejected() {
        LoggedFilterConfiguration.Builder builder = LoggedFilterConfiguration.builder();

        assertThrows(NullPointerException.class, () -> builder.requestId(null));
        assertThrows(NullPointerException.class, () -> builder.sensitiveParameters(null));
        assertThrows(NullPointerException.class, () -> builder.responseLevel(null));
        assertThrows(NullPointerException.class, () -> builder.bodyCapture(null));
        assertThrows(NullPointerException.class, () -> builder.withoutField(null));
        assertThrows(NullPointerException.class, () -> new LoggedFilter(null));
    }

}
