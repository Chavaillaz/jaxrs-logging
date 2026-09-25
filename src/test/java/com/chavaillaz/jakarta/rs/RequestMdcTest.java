package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_ID;
import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_PARAMETERS;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_STATUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("Request MDC")
class RequestMdcTest {

    final Map<LoggedField, String> fieldNames = new EnumMap<>(LoggedFilterConfiguration.defaults().fieldNames());
    final RequestMdc mdc = new RequestMdc(fieldNames);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    static LoggedRequestState request() {
        return new LoggedRequestState(new LoggedFilter());
    }

    @Test
    @DisplayName("Check an entry put for a request is removed once that request is done")
    void checkEntryRemovedWithItsRequest() {
        LoggedRequestState request = request();
        mdc.start(request);

        mdc.put("custom-key", "custom-value");
        mdc.put(REQUEST_ID, "abc-123");
        assertEquals("custom-value", MDC.get("custom-key"));
        assertEquals("abc-123", mdc.get(REQUEST_ID));

        mdc.cleanup(request);
        assertNull(MDC.get("custom-key"));
        assertNull(mdc.get(REQUEST_ID));
    }

    @Test
    @DisplayName("Check a blank value is not put, rather than being logged as an always-empty entry")
    void checkBlankValueNotPut() {
        mdc.start(request());

        mdc.put("custom-key", " ");

        assertNull(MDC.get("custom-key"));
    }

    @Test
    @DisplayName("Check a field without a name is neither put nor read")
    void checkUnnamedFieldLeftOut() {
        fieldNames.remove(REQUEST_PARAMETERS);
        mdc.start(request());

        mdc.put(REQUEST_PARAMETERS, "topic=news");

        assertNull(mdc.get(REQUEST_PARAMETERS));
        assertFalse(MDC.getCopyOfContextMap().containsValue("topic=news"));
    }

    @Test
    @DisplayName("Check starting a request sweeps what a previous one left on the thread")
    void checkStartSweepsPreviousRequest() {
        // Given: a request that completed elsewhere, leaving its entries on this thread
        mdc.start(request());
        mdc.put("header-User-Agent", "JUnit");
        mdc.put(REQUEST_ID, "previous");

        // When
        mdc.start(request());

        // Then
        assertNull(MDC.get("header-User-Agent"));
        assertNull(mdc.get(REQUEST_ID));
    }

    @Test
    @DisplayName("Check starting a request sweeps what one done on this thread left, even once put back")
    void checkStartSweepsRestoredEntries() {
        // Given: a request completed within a wrapper that saved the context map before, and restores it after
        LoggedRequestState previous = request();
        mdc.start(previous);
        mdc.put("header-X-Tenant", "acme");
        Map<String, String> saved = MDC.getCopyOfContextMap();
        previous.markCompleted();
        mdc.cleanup(previous);
        MDC.setContextMap(saved);

        // When
        mdc.start(request());

        // Then
        assertNull(MDC.get("header-X-Tenant"));
    }

    @Test
    @DisplayName("Check a callback of a request done leaves nothing on the thread that carried it")
    void checkLateCallbackLeavesNothing() {
        // Given
        LoggedRequestState request = request();
        mdc.start(request);
        request.markCompleted();
        mdc.cleanup(request);

        // When
        mdc.onBehalfOf(request, () -> mdc.put(RESPONSE_STATUS, "500"));

        // Then
        assertNull(mdc.get(RESPONSE_STATUS));
    }

    @Test
    @DisplayName("Check a request done stops recording its bodies, which its thread would keep until the next one")
    void checkBodiesNotKept() {
        // Given
        LoggedRequestState request = request();
        mdc.start(request);
        mdc.put("custom-key", "custom-value");
        mdc.put(REQUEST_BODY, "request body");
        mdc.put(RESPONSE_BODY, "response body");

        // When
        request.markCompleted();
        mdc.cleanup(request);

        // Then
        assertEquals(Map.of("custom-key", "custom-value"), request.getMdcEntries());
    }

    @Test
    @DisplayName("Check entries put on behalf of a request completing within another are that request's")
    void checkNestedRequestKeepsEnclosingOneTracked() {
        // Given: a request suspended earlier, and the request resuming it on this thread
        LoggedRequestState suspended = request();
        mdc.start(suspended);
        LoggedRequestState enclosing = request();
        mdc.start(enclosing);
        mdc.put("enclosing-key", "enclosing");

        // When: the suspended request completes within the enclosing one
        mdc.onBehalfOf(suspended, () -> {
            mdc.put("suspended-key", "suspended");
            suspended.markCompleted();
            mdc.cleanup(suspended);
        });

        // Then: its own entries are gone, the enclosing request's are untouched, and still removed with it
        assertNull(MDC.get("suspended-key"));
        assertEquals("enclosing", MDC.get("enclosing-key"));
        mdc.cleanup(enclosing);
        assertNull(MDC.get("enclosing-key"));
    }

    @Test
    @DisplayName("Check a thread acting for a request it does not carry is lent its entries, then given its own back")
    void checkEntriesLentToThreadCarryingAnotherRequest() {
        // Given: a request suspended earlier, and the request resuming it on this thread
        LoggedRequestState suspended = request();
        mdc.start(suspended);
        mdc.put(REQUEST_ID, "suspended");
        LoggedRequestState enclosing = request();
        mdc.start(enclosing);
        mdc.put(REQUEST_ID, "enclosing");

        // When: the suspended request completes within the enclosing one
        AtomicReference<String> seen = new AtomicReference<>();
        mdc.onBehalfOf(suspended, () -> {
            seen.set(mdc.get(REQUEST_ID));
            suspended.markCompleted();
            mdc.cleanup(suspended);
        });

        // Then: it completed as itself, and the enclosing request still has its own identifier
        assertEquals("suspended", seen.get());
        assertEquals("enclosing", mdc.get(REQUEST_ID));
    }

    @Test
    @DisplayName("Check a thread carrying no request is lent the entries of one it acts for, and left without them")
    void checkEntriesLentToIdleThread() throws Exception {
        // Given
        LoggedRequestState request = request();
        mdc.start(request);
        mdc.put(REQUEST_ID, "abc-123");
        ExecutorService worker = Executors.newSingleThreadExecutor();

        // When: a response filter runs on a worker, without completing the request
        record Observed(String requestId, Map<String, String> left) {
        }
        Observed observed;
        try {
            observed = worker.submit(() -> {
                AtomicReference<String> seen = new AtomicReference<>();
                mdc.onBehalfOf(request, () -> {
                    seen.set(mdc.get(REQUEST_ID));
                    mdc.put(RESPONSE_STATUS, "200");
                });
                return new Observed(seen.get(), MDC.getCopyOfContextMap());
            }).get();
        } finally {
            worker.shutdown();
        }

        // Then: what the worker put is the request's, and nothing of it is left on the worker
        assertEquals("abc-123", observed.requestId());
        assertTrue(observed.left() == null || observed.left().isEmpty(), () -> "Left on the worker: " + observed.left());
        assertEquals("200", request.getMdcEntries().get(RESPONSE_STATUS.getDefaultField()));
    }

    @Test
    @DisplayName("Check the fields are recognized by name, whatever renames them")
    void checkFieldsRecognized() {
        fieldNames.put(REQUEST_ID, "request-identifier");

        assertTrue(mdc.isField("request-identifier"));
        assertFalse(mdc.isField("request-id"));
    }

}
