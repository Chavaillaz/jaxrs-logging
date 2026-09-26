package com.chavaillaz.jakarta.rs.mdc;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("MDC propagation to an asynchronous response")
class MdcPropagationAsyncResponseTest {

    /**
     * Stands in for the container's own {@link AsyncResponse}: what matters here is only what the MDC
     * looks like at the moment the request is completed, since that is when the container runs the
     * response filters and this library writes its "Processed ..." line.
     */
    static class RecordingAsyncResponse implements AsyncResponse {

        final AtomicReference<String> contextOnCompletion = new AtomicReference<>();
        TimeoutHandler timeoutHandler;

        private boolean complete() {
            contextOnCompletion.set(MDC.get("request-id"));
            return true;
        }

        @Override
        public boolean resume(Object response) {
            return complete();
        }

        @Override
        public boolean resume(Throwable response) {
            return complete();
        }

        @Override
        public boolean cancel() {
            return complete();
        }

        @Override
        public boolean cancel(int retryAfter) {
            return complete();
        }

        @Override
        public boolean cancel(Date retryAfter) {
            return complete();
        }

        @Override
        public void setTimeoutHandler(TimeoutHandler handler) {
            this.timeoutHandler = handler;
        }

        @Override
        public boolean isSuspended() {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean setTimeout(long time, TimeUnit unit) {
            return true;
        }

        @Override
        public java.util.Collection<Class<?>> register(Class<?> callback) {
            return java.util.List.of();
        }

        @Override
        public java.util.Map<Class<?>, java.util.Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
            return java.util.Map.of();
        }

        @Override
        public java.util.Collection<Class<?>> register(Object callback) {
            return java.util.List.of();
        }

        @Override
        public java.util.Map<Class<?>, java.util.Collection<Class<?>>> register(Object callback, Object... callbacks) {
            return java.util.Map.of();
        }

    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Check resuming from another thread completes the request with the request's context")
    void checkResumeCarriesRequestContext() throws Exception {
        ExecutorService pool = newSingleThreadExecutor();
        try {
            // Given: the resource method's thread, holding the MDC this library set for the request
            MDC.put("request-id", "abc-123");
            RecordingAsyncResponse response = new RecordingAsyncResponse();
            AsyncResponse propagating = MdcPropagation.wrap(response);

            // When: the application resumes from a thread that knows nothing about the request, and is
            // not itself wrapped
            pool.execute(() -> propagating.resume("done"));

            // Then
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, SECONDS));
            assertEquals("abc-123", response.contextOnCompletion.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Check an unwrapped response completes with no context, as the wrapper is what fixes")
    void checkUnwrappedResponseHasNoContext() throws Exception {
        ExecutorService pool = newSingleThreadExecutor();
        try {
            MDC.put("request-id", "abc-123");
            RecordingAsyncResponse response = new RecordingAsyncResponse();

            pool.execute(() -> response.resume("done"));

            pool.shutdown();
            assertTrue(pool.awaitTermination(5, SECONDS));
            assertNull(response.contextOnCompletion.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Check resuming with a failure carries the context too")
    void checkResumeWithThrowableCarriesContext() {
        MDC.put("request-id", "abc-123");
        RecordingAsyncResponse response = new RecordingAsyncResponse();
        AsyncResponse propagating = MdcPropagation.wrap(response);
        MDC.clear();

        propagating.resume(new IllegalStateException("failed"));

        assertEquals("abc-123", response.contextOnCompletion.get());
    }

    @Test
    @DisplayName("Check cancelling carries the context, as it completes the request as well")
    void checkCancelCarriesContext() {
        MDC.put("request-id", "abc-123");
        RecordingAsyncResponse response = new RecordingAsyncResponse();
        AsyncResponse propagating = MdcPropagation.wrap(response);
        MDC.clear();

        propagating.cancel(5);

        assertEquals("abc-123", response.contextOnCompletion.get());
    }

    @Test
    @DisplayName("Check a timeout handler is invoked with the context and with the wrapped response")
    void checkTimeoutHandlerCarriesContext() {
        // Given: the container invokes the handler on a timer thread, with the response it owns, so
        // without wrapping it a timeout would complete the request with no context at all
        MDC.put("request-id", "abc-123");
        RecordingAsyncResponse response = new RecordingAsyncResponse();
        AsyncResponse propagating = MdcPropagation.wrap(response);
        AtomicReference<String> contextInHandler = new AtomicReference<>();
        propagating.setTimeoutHandler(timedOut -> {
            contextInHandler.set(MDC.get("request-id"));
            timedOut.resume("timeout");
        });
        MDC.clear();

        // When: the container times the request out
        response.timeoutHandler.handleTimeout(response);

        // Then
        assertEquals("abc-123", contextInHandler.get());
        assertEquals("abc-123", response.contextOnCompletion.get());
    }

    @Test
    @DisplayName("Check clearing the timeout handler clears it on the response")
    void checkTimeoutHandlerCleared() {
        // Given: a handler set, then cleared so the container times the request out its own way again
        RecordingAsyncResponse response = new RecordingAsyncResponse();
        AsyncResponse propagating = MdcPropagation.wrap(response);
        propagating.setTimeoutHandler(timedOut -> timedOut.resume("timeout"));

        // When
        propagating.setTimeoutHandler(null);

        // Then: wrapped, it used to become a handler failing on the timeout it was meant to leave alone
        assertNull(response.timeoutHandler);
    }

    @Test
    @DisplayName("Check the completing thread keeps its own context afterwards")
    void checkCompletingThreadContextRestored() {
        MDC.put("request-id", "abc-123");
        AsyncResponse propagating = MdcPropagation.wrap(new RecordingAsyncResponse());
        MDC.put("request-id", "other-request");

        propagating.resume("done");

        // The thread completing this request may well be in the middle of handling another one
        assertEquals("other-request", MDC.get("request-id"));
    }

    @Test
    @DisplayName("Check the methods not completing the request delegate as-is")
    void checkOtherMethodsDelegate() {
        RecordingAsyncResponse response = new RecordingAsyncResponse();
        AsyncResponse propagating = MdcPropagation.wrap(response);

        assertTrue(propagating.isSuspended());
        assertTrue(propagating.setTimeout(1, SECONDS));
        assertTrue(propagating.register(Object.class).isEmpty());
        assertNull(response.contextOnCompletion.get());
    }

}
