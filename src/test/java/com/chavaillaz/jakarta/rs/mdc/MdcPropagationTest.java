package com.chavaillaz.jakarta.rs.mdc;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
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
            assertTrue(latch.await(5, SECONDS));
            assertEquals("for-execute", executeResult.get());

            // submit(Runnable)
            MDC.put("request-id", "for-submit-runnable");
            AtomicReference<String> submitResult = new AtomicReference<>();
            executor.submit(() -> submitResult.set(MDC.get("request-id"))).get(5, SECONDS);
            assertEquals("for-submit-runnable", submitResult.get());

            // submit(Callable)
            MDC.put("request-id", "for-submit-callable");
            Future<String> future = executor.submit(() -> MDC.get("request-id"));
            assertEquals("for-submit-callable", future.get(5, SECONDS));

            // invokeAll
            MDC.put("request-id", "for-invoke-all");
            List<Future<String>> results = executor.invokeAll(List.of(() -> MDC.get("request-id")));
            assertEquals("for-invoke-all", results.getFirst().get(5, SECONDS));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, SECONDS));
        }
    }

    @Test
    @DisplayName("Check a null task is rejected where it is submitted, as an unwrapped executor rejects it")
    void checkNullTaskRejectedOnSubmission() throws Exception {
        // Wrapped into a task that is not null itself, it used to be accepted, and to fail only once run,
        // on a pool thread, far from the code that submitted it - killing that thread in the process
        ExecutorService executor = MdcPropagation.wrap(Executors.newSingleThreadExecutor());
        try {
            assertThrows(NullPointerException.class, () -> executor.execute(null));
            assertThrows(NullPointerException.class, () -> executor.submit((Runnable) null));
            assertThrows(NullPointerException.class, () -> executor.submit((Callable<?>) null));
            assertThrows(NullPointerException.class, () -> MdcPropagation.wrap((Runnable) null));
            assertThrows(NullPointerException.class, () -> MdcPropagation.wrapSupplier(null));
            assertThrows(NullPointerException.class, () -> MdcPropagation.wrap((ExecutorService) null));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, SECONDS));
        }
    }

    @Test
    @DisplayName("Check lifecycle methods of a wrapped ExecutorService delegate to the underlying executor")
    void checkWrappedExecutorServiceDelegatesLifecycle() throws Exception {
        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        ExecutorService executor = MdcPropagation.wrap(rawExecutor);

        assertFalse(executor.isShutdown());
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, SECONDS));
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(rawExecutor.isShutdown());
    }

    @Test
    @DisplayName("Check closing a wrapped ExecutorService closes the underlying executor its own way")
    void checkWrappedExecutorServiceDelegatesClose() {
        // Given: the common pool cannot be shut down, so its own close() does nothing, while the default
        // close() the wrapper used to inherit waits for it to terminate forever - as a try-with-resources
        // block or a dependency injection container closing its beans on shutdown would
        ExecutorService executor = MdcPropagation.wrap(ForkJoinPool.commonPool());

        // When / Then
        assertTimeoutPreemptively(Duration.ofSeconds(5), executor::close);
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
            }, 1, MILLISECONDS);
            assertTrue(latch.await(5, SECONDS));
            assertEquals("for-schedule-runnable", runnableResult.get());

            // schedule(Callable, ...)
            MDC.put("request-id", "for-schedule-callable");
            Future<String> future = executor.schedule(() -> MDC.get("request-id"), 1, MILLISECONDS);
            assertEquals("for-schedule-callable", future.get(5, SECONDS));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, SECONDS));
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
            }, 0, 5, MILLISECONDS);
            MDC.put("request-id", "mutated-after-scheduling");

            // Then
            assertTrue(latch.await(5, SECONDS));
            assertTrue(seen.stream().allMatch("captured-at-schedule-time"::equals));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, SECONDS));
        }
    }

    @Test
    @DisplayName("Check lifecycle methods of a wrapped ScheduledExecutorService delegate to the underlying executor")
    void checkWrappedScheduledExecutorServiceDelegatesLifecycle() throws Exception {
        ScheduledExecutorService rawExecutor = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService executor = MdcPropagation.wrap(rawExecutor);

        assertFalse(executor.isShutdown());
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, SECONDS));
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(rawExecutor.isShutdown());
    }

    @Test
    @DisplayName("Check a wrapped Executor carries context through a whole CompletableFuture chain")
    void checkExecutorCarriesCompletableFutureChain() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Given
            MDC.put("request-id", "abc-123");
            Executor executor = MdcPropagation.wrap(pool);
            List<String> seen = new CopyOnWriteArrayList<>();

            // When: every stage runs on a pool thread, and each one submits the next
            String result = CompletableFuture
                    .supplyAsync(() -> record(seen, "loaded"), executor)
                    .thenApplyAsync(value -> value + record(seen, ""), executor)
                    .thenApplyAsync(value -> value + record(seen, ""), executor)
                    .get(5, SECONDS);

            // Then: the context reaches every stage, not just the first
            assertEquals("loaded", result);
            assertEquals(3, seen.size());
            assertTrue(seen.stream().allMatch("abc-123"::equals));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, SECONDS));
        }
    }

    private static String record(List<String> seen, String result) {
        seen.add(MDC.get("request-id"));
        return result;
    }

    @Test
    @DisplayName("Check a wrapped Executor restores the running thread's own context after each task")
    void checkExecutorRestoresContext() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Given: a task submitted with a context, then one submitted without
            MDC.put("request-id", "abc-123");
            Executor executor = MdcPropagation.wrap(pool);
            executor.execute(() -> {
                // No-op, only there to leave a context behind on the pool thread
            });
            MDC.clear();

            // When
            AtomicReference<String> seen = new AtomicReference<>("not-run");
            CountDownLatch latch = new CountDownLatch(1);
            executor.execute(() -> {
                seen.set(MDC.get("request-id"));
                latch.countDown();
            });

            // Then
            assertTrue(latch.await(5, SECONDS));
            assertNull(seen.get());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, SECONDS));
        }
    }

    @Test
    @DisplayName("Check the wrapped CompletableFuture stage functions see the wrapping thread's context")
    void checkStageFunctionsSeeWrappingThreadContext() throws Exception {
        // Given: the common ForkJoinPool, which cannot be wrapped, so the functions themselves are
        MDC.put("request-id", "abc-123");
        List<String> seen = new CopyOnWriteArrayList<>();

        // When
        String result = CompletableFuture
                .supplyAsync(MdcPropagation.wrapSupplier(() -> record(seen, "loaded")))
                .thenApplyAsync(MdcPropagation.wrapFunction(value -> value + record(seen, "")))
                .get(5, SECONDS);

        AtomicReference<String> consumed = new AtomicReference<>();
        CompletableFuture
                .completedFuture(result)
                .thenAcceptAsync(MdcPropagation.wrapConsumer(value -> consumed.set(MDC.get("request-id"))))
                .get(5, SECONDS);

        AtomicReference<String> completed = new AtomicReference<>();
        CompletableFuture
                .completedFuture(result)
                .whenCompleteAsync(MdcPropagation.wrapBiConsumer((value, error) -> completed.set(MDC.get("request-id"))))
                .get(5, SECONDS);

        AtomicReference<String> handled = new AtomicReference<>();
        CompletableFuture
                .completedFuture(result)
                .handleAsync(MdcPropagation.wrapBiFunction((value, error) -> {
                    handled.set(MDC.get("request-id"));
                    return value;
                }))
                .get(5, SECONDS);

        // Then
        assertEquals("loaded", result);
        assertEquals(2, seen.size());
        assertTrue(seen.stream().allMatch("abc-123"::equals));
        assertEquals("abc-123", consumed.get());
        assertEquals("abc-123", completed.get());
        assertEquals("abc-123", handled.get());
    }

}
