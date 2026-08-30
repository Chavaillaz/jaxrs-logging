package com.chavaillaz.jakarta.rs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("MDC propagation")
class MdcPropagationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Check a wrapped Runnable sees the submitting thread's MDC context on another thread")
    void checkRunnableSeesSubmittingThreadContext() throws Exception {
        // Given
        MDC.put("request-id", "abc-123");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = MdcPropagation.wrap(() -> seen.set(MDC.get("request-id")));

        // When
        Thread thread = new Thread(task);
        thread.start();
        thread.join();

        // Then
        assertEquals("abc-123", seen.get());
    }

    @Test
    @DisplayName("Check a wrapped Runnable captures context at wrap time, not at run time")
    void checkRunnableCapturesContextAtWrapTime() throws Exception {
        // Given
        MDC.put("request-id", "captured-at-wrap-time");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = MdcPropagation.wrap(() -> seen.set(MDC.get("request-id")));
        MDC.put("request-id", "mutated-after-wrap-before-run");

        // When
        Thread thread = new Thread(task);
        thread.start();
        thread.join();

        // Then
        assertEquals("captured-at-wrap-time", seen.get());
    }

    @Test
    @DisplayName("Check the running thread's own MDC context is restored after the wrapped task completes")
    void checkPreviousContextRestoredAfterRunnable() throws Exception {
        // Given
        MDC.put("request-id", "submitter-context");
        Runnable task = MdcPropagation.wrap(() -> {
        });

        // When: the pooled thread already has its own MDC entry from a previous task
        AtomicReference<String> afterCompletion = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            MDC.put("request-id", "pooled-thread-leftover");
            task.run();
            afterCompletion.set(MDC.get("request-id"));
        });
        thread.start();
        thread.join();

        // Then
        assertEquals("pooled-thread-leftover", afterCompletion.get());
    }

    @Test
    @DisplayName("Check a wrapped Runnable clears MDC on the running thread when none was set at wrap time")
    void checkRunnableClearsMdcWhenNoneCapturedAtWrapTime() throws Exception {
        // Given: nothing in MDC when wrapping
        Runnable task = MdcPropagation.wrap(() -> {
        });
        AtomicReference<String> seenDuringRun = new AtomicReference<>();
        Runnable checking = MdcPropagation.wrap(() -> seenDuringRun.set(MDC.get("leftover")));

        // When: the running thread has a leftover entry from a previous, unrelated task
        Thread thread = new Thread(() -> {
            MDC.put("leftover", "should-not-be-visible");
            checking.run();
        });
        thread.start();
        thread.join();

        // Then
        assertNull(seenDuringRun.get());
    }

    @Test
    @DisplayName("Check a wrapped Callable sees the submitting thread's MDC context and returns its result")
    void checkCallableSeesSubmittingThreadContext() throws Exception {
        // Given
        MDC.put("request-id", "abc-123");
        Callable<String> task = MdcPropagation.wrap(() -> MDC.get("request-id"));
        AtomicReference<String> seen = new AtomicReference<>();

        // When
        Thread thread = new Thread(() -> {
            try {
                seen.set(task.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        thread.start();
        thread.join();

        // Then
        assertEquals("abc-123", seen.get());
    }

    @Test
    @DisplayName("Check a wrapped ExecutorService propagates context through execute, submit and invokeAll")
    void checkWrappedExecutorServicePropagatesContext() throws Exception {
        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        ExecutorService executor = MdcPropagation.wrap(rawExecutor);
        try {
            // execute
            MDC.put("request-id", "for-execute");
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> executeResult = new AtomicReference<>();
            executor.execute(() -> {
                executeResult.set(MDC.get("request-id"));
                latch.countDown();
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("for-execute", executeResult.get());

            // submit(Runnable)
            MDC.put("request-id", "for-submit-runnable");
            AtomicReference<String> submitResult = new AtomicReference<>();
            executor.submit(() -> submitResult.set(MDC.get("request-id"))).get(5, TimeUnit.SECONDS);
            assertEquals("for-submit-runnable", submitResult.get());

            // submit(Callable)
            MDC.put("request-id", "for-submit-callable");
            Future<String> future = executor.submit(() -> MDC.get("request-id"));
            assertEquals("for-submit-callable", future.get(5, TimeUnit.SECONDS));

            // invokeAll
            MDC.put("request-id", "for-invoke-all");
            List<Future<String>> results = executor.invokeAll(List.of(() -> MDC.get("request-id")));
            assertEquals("for-invoke-all", results.getFirst().get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("Check lifecycle methods of a wrapped ExecutorService delegate to the underlying executor")
    void checkWrappedExecutorServiceDelegatesLifecycle() throws Exception {
        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        ExecutorService executor = MdcPropagation.wrap(rawExecutor);

        assertFalse(executor.isShutdown());
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(rawExecutor.isShutdown());
    }

    @Test
    @DisplayName("Check a wrapped ScheduledExecutorService propagates context through schedule")
    void checkWrappedScheduledExecutorServicePropagatesContextThroughSchedule() throws Exception {
        ScheduledExecutorService rawExecutor = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService executor = MdcPropagation.wrap(rawExecutor);
        try {
            // schedule(Runnable, ...)
            MDC.put("request-id", "for-schedule-runnable");
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> runnableResult = new AtomicReference<>();
            executor.schedule(() -> {
                runnableResult.set(MDC.get("request-id"));
                latch.countDown();
            }, 1, TimeUnit.MILLISECONDS);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("for-schedule-runnable", runnableResult.get());

            // schedule(Callable, ...)
            MDC.put("request-id", "for-schedule-callable");
            Future<String> future = executor.schedule(() -> MDC.get("request-id"), 1, TimeUnit.MILLISECONDS);
            assertEquals("for-schedule-callable", future.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("Check a periodic task captures MDC context once at scheduling time, reused for every execution")
    void checkWrappedScheduledExecutorServiceReusesSnapshotAcrossPeriodicExecutions() throws Exception {
        ScheduledExecutorService rawExecutor = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService executor = MdcPropagation.wrap(rawExecutor);
        try {
            // Given
            MDC.put("request-id", "captured-at-schedule-time");
            List<String> seen = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(3);

            // When: the scheduling thread's MDC is mutated after scheduling, before most executions run
            executor.scheduleAtFixedRate(() -> {
                seen.add(MDC.get("request-id"));
                latch.countDown();
            }, 0, 5, TimeUnit.MILLISECONDS);
            MDC.put("request-id", "mutated-after-scheduling");

            // Then
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(seen.stream().allMatch("captured-at-schedule-time"::equals));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("Check lifecycle methods of a wrapped ScheduledExecutorService delegate to the underlying executor")
    void checkWrappedScheduledExecutorServiceDelegatesLifecycle() throws Exception {
        ScheduledExecutorService rawExecutor = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService executor = MdcPropagation.wrap(rawExecutor);

        assertFalse(executor.isShutdown());
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(rawExecutor.isShutdown());
    }

}
