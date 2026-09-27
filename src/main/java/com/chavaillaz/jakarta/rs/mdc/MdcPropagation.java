package com.chavaillaz.jakarta.rs.mdc;

import static java.util.Objects.requireNonNull;

import jakarta.ws.rs.container.AsyncResponse;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import com.chavaillaz.jakarta.rs.LoggedFeature;

/**
 * Propagates the MDC context map of the calling thread to a task run on another one.
 * <p>
 * MDC is thread-local, so the entries {@link LoggedFeature} puts for a request are not visible to a task handed
 * to an {@link ExecutorService}, a {@link Thread}, or a {@code @Suspended} response resumed elsewhere. A task
 * wrapped here runs with a copy of the context map of the thread wrapping it, and gives its thread back its own
 * context map once done, so a pooled thread never carries the entries of one task into the next.
 * <p>
 * Every method rejects a {@code null} argument right away, rather than once run on a pool thread.
 */
public final class MdcPropagation {

    private MdcPropagation() {
        // Utility class
    }

    /**
     * Wraps the given task to run with a copy of the context map the calling thread has now.
     *
     * @param task The task to wrap
     * @return A task running the given one with the calling thread's MDC context map applied
     */
    public static Runnable wrap(Runnable task) {
        requireNonNull(task, "task");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(context);
            try {
                task.run();
            } finally {
                setContext(previous);
            }
        };
    }

    /**
     * Wraps the given task to run with a copy of the context map the calling thread has now.
     *
     * @param task The task to wrap
     * @param <T>  The type of the result returned by the task
     * @return A task running the given one with the calling thread's MDC context map applied
     */
    public static <T extends @Nullable Object> Callable<T> wrap(Callable<T> task) {
        requireNonNull(task, "task");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(context);
            try {
                return task.call();
            } finally {
                setContext(previous);
            }
        };
    }

    /**
     * Wraps the given executor so every task submitted to it runs with a copy of the context map of the thread
     * submitting it, the other methods delegated as they are.
     *
     * @param executor The executor to wrap
     * @return An executor propagating MDC context to every task it runs
     */
    public static ExecutorService wrap(ExecutorService executor) {
        requireNonNull(executor, "executor");
        return new MdcPropagatingExecutorService(executor);
    }

    /**
     * Wraps the given executor so every task submitted to it runs with a copy of the context map of the thread
     * submitting it, the other methods delegated as they are. A periodic task runs every time with the context
     * map captured when it was scheduled.
     *
     * @param executor The executor to wrap
     * @return An executor propagating MDC context to every task it runs
     */
    public static ScheduledExecutorService wrap(ScheduledExecutorService executor) {
        requireNonNull(executor, "executor");
        return new MdcPropagatingScheduledExecutorService(executor);
    }

    /**
     * Wraps the given executor so every task runs with a copy of the context map of the thread submitting it.
     * Given to every stage of a {@link java.util.concurrent.CompletableFuture} chain, it carries the context along
     * the chain, each stage submitting the next from a thread having it.
     * <pre>{@code
     * Executor executor = MdcPropagation.wrap(pool);
     * CompletableFuture.supplyAsync(() -> load(id), executor)
     *         .thenApplyAsync(this::render, executor)
     *         .thenAcceptAsync(response::resume, executor);
     * }</pre>
     * The {@code *Async} methods taking no executor run on the common {@link java.util.concurrent.ForkJoinPool},
     * which cannot be wrapped: wrap their functions instead (see {@link #wrapSupplier} and its siblings).
     *
     * @param executor The executor to wrap
     * @return An executor propagating MDC context to every task it runs
     */
    public static Executor wrap(Executor executor) {
        requireNonNull(executor, "executor");
        return task -> executor.execute(wrap(task));
    }

    /**
     * Wraps the given supplier to run with a copy of the context map the calling thread has now, for
     * {@link java.util.concurrent.CompletableFuture#supplyAsync(Supplier)}. Named apart from {@code wrap}, like
     * the functions below, as {@link Supplier} has the shape of {@link Callable} and {@link Function} of
     * {@link Consumer}: an overload would make {@code wrap(() -> value)} ambiguous.
     *
     * @param task The supplier to wrap
     * @param <T>  The type of the result returned by the supplier
     * @return A supplier running the given one with the calling thread's MDC context map applied
     */
    public static <T extends @Nullable Object> Supplier<T> wrapSupplier(Supplier<T> task) {
        requireNonNull(task, "task");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> inContext(context, task);
    }

    /**
     * Wraps the given function the way {@link #wrapSupplier} wraps a supplier, for
     * {@link java.util.concurrent.CompletableFuture#thenApplyAsync(Function)} and
     * {@link java.util.concurrent.CompletableFuture#thenComposeAsync(Function)}.
     *
     * @param task The function to wrap
     * @param <T>  The type of the argument taken by the function
     * @param <R>  The type of the result returned by the function
     * @return A function running the given one with the calling thread's MDC context map applied
     */
    public static <T extends @Nullable Object, R extends @Nullable Object> Function<T, R> wrapFunction(Function<T, R> task) {
        requireNonNull(task, "task");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return value -> inContext(context, () -> task.apply(value));
    }

    /**
     * Wraps the given consumer the way {@link #wrapSupplier} wraps a supplier, for
     * {@link java.util.concurrent.CompletableFuture#thenAcceptAsync(Consumer)}.
     *
     * @param task The consumer to wrap
     * @param <T>  The type of the argument taken by the consumer
     * @return A consumer running the given one with the calling thread's MDC context map applied
     */
    public static <T extends @Nullable Object> Consumer<T> wrapConsumer(Consumer<T> task) {
        requireNonNull(task, "task");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return value -> inContext(context, () -> {
            task.accept(value);
            return null;
        });
    }

    /**
     * Wraps the given function the way {@link #wrapSupplier} wraps a supplier, for
     * {@link java.util.concurrent.CompletableFuture#handleAsync(BiFunction)} and
     * {@link java.util.concurrent.CompletableFuture#thenCombineAsync(java.util.concurrent.CompletionStage, BiFunction)}.
     *
     * @param task The function to wrap
     * @param <T>  The type of the first argument taken by the function
     * @param <U>  The type of the second argument taken by the function
     * @param <R>  The type of the result returned by the function
     * @return A function running the given one with the calling thread's MDC context map applied
     */
    public static <T extends @Nullable Object, U extends @Nullable Object, R extends @Nullable Object> BiFunction<T, U, R> wrapBiFunction(BiFunction<T, U, R> task) {
        requireNonNull(task, "task");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return (first, second) -> inContext(context, () -> task.apply(first, second));
    }

    /**
     * Wraps the given consumer the way {@link #wrapSupplier} wraps a supplier, for
     * {@link java.util.concurrent.CompletableFuture#whenCompleteAsync(BiConsumer)}.
     *
     * @param task The consumer to wrap
     * @param <T>  The type of the first argument taken by the consumer
     * @param <U>  The type of the second argument taken by the consumer
     * @return A consumer running the given one with the calling thread's MDC context map applied
     */
    public static <T extends @Nullable Object, U extends @Nullable Object> BiConsumer<T, U> wrapBiConsumer(BiConsumer<T, U> task) {
        requireNonNull(task, "task");
        Map<String, String> context = MDC.getCopyOfContextMap();
        return (first, second) -> inContext(context, () -> {
            task.accept(first, second);
            return null;
        });
    }

    /**
     * Wraps the given asynchronous response so the request completes with a copy of the context map the calling
     * thread has now, whichever thread resumes it: the response filters and message body writers of the
     * application then log with the entries of the request, which {@link LoggedFeature} lends its own lines
     * anyway.
     * <pre>{@code
     * @GET
     * public void get(@Suspended AsyncResponse response) {
     *     AsyncResponse propagating = MdcPropagation.wrap(response);
     *     pool.execute(() -> propagating.resume(load()));
     * }
     * }</pre>
     * It covers the completion however it is reached - a pool, a {@code CompletableFuture} chain, a client
     * callback - and the timeout handler. Only the methods completing the request apply the context.
     *
     * @param response The asynchronous response to wrap
     * @return An asynchronous response completing the request with the calling thread's MDC context map
     */
    public static AsyncResponse wrap(AsyncResponse response) {
        requireNonNull(response, "response");
        return new MdcPropagatingAsyncResponse(response, MDC.getCopyOfContextMap());
    }

    /**
     * Runs the given action with the given context map, giving the running thread its own back once done.
     *
     * @param context The context map to apply while running the action, {@code null} to run it with none
     * @param action  The action to run
     * @param <T>     The type of the result returned by the action
     * @return The result of the action
     */
    static <T extends @Nullable Object> T inContext(@Nullable Map<String, String> context, Supplier<T> action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        setContext(context);
        try {
            return action.get();
        } finally {
            setContext(previous);
        }
    }

    private static void setContext(@Nullable Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

}
