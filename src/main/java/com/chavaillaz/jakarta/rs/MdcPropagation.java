package com.chavaillaz.jakarta.rs;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
import org.slf4j.MDC;

/**
 * Propagates the calling thread's MDC context map to a task run on another thread.
 * <p>
 * MDC is backed by a thread-local, so entries set by {@link LoggedFilter} (or by application code) on the
 * thread handling a request are not visible to a task submitted to an {@link ExecutorService}, a manually
 * started {@link Thread}, or any other thread hand-off - including a {@code @Suspended AsyncResponse} or a
 * reactive resource method resuming on a different worker thread, as already documented on
 * {@link LoggedRequestState#getMdcCloseables()}. Wrap a task (or a whole {@link ExecutorService}) with this
 * class to copy the submitting thread's MDC context map onto the thread that actually runs it, and restore
 * that thread's own previous context map once the task completes - rather than merging into it, so a
 * pooled thread never leaks one task's MDC entries into the next one it happens to run.
 */
public final class MdcPropagation {

    private MdcPropagation() {
        // Utility class
    }

    /**
     * Wraps the given task so it runs with a copy of the calling thread's current MDC context map,
     * captured at the time this method is called (not when the returned task is eventually run).
     *
     * @param task The task to wrap
     * @return A task running the given one with the calling thread's MDC context map applied
     */
    public static Runnable wrap(Runnable task) {
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
     * Wraps the given task so it runs with a copy of the calling thread's current MDC context map,
     * captured at the time this method is called (not when the returned task is eventually run).
     *
     * @param task The task to wrap
     * @param <T>  The type of the result returned by the task
     * @return A task running the given one with the calling thread's MDC context map applied
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
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
     * Wraps the given executor so every task submitted to it (through any of {@link ExecutorService}'s
     * task-accepting methods) runs with a copy of the submitting thread's MDC context map, captured at
     * submission time.
     * <p>
     * Lifecycle methods ({@link ExecutorService#shutdown()}, {@link ExecutorService#awaitTermination}, ...)
     * are delegated as-is to the given executor.
     *
     * @param executor The executor to wrap
     * @return An executor propagating MDC context to every task it runs
     */
    public static ExecutorService wrap(ExecutorService executor) {
        return new MdcPropagatingExecutorService(executor);
    }

    /**
     * Wraps the given executor so every task submitted to it (through any of {@link ExecutorService}'s or
     * {@link ScheduledExecutorService}'s task-accepting methods) runs with a copy of the submitting
     * thread's MDC context map, captured at submission time.
     * <p>
     * For a periodic task ({@link ScheduledExecutorService#scheduleAtFixedRate} or
     * {@link ScheduledExecutorService#scheduleWithFixedDelay}), the context map is captured once, when the
     * task is scheduled, and that same snapshot is applied to every execution of it - consistent with how
     * every other method here captures context at wrap/submission time rather than at run time (see
     * {@link #wrap(Runnable)}). A later change to the scheduling thread's MDC context is not picked up by
     * executions that already started running before that change.
     * <p>
     * Lifecycle methods ({@link ExecutorService#shutdown()}, {@link ExecutorService#awaitTermination}, ...)
     * are delegated as-is to the given executor.
     *
     * @param executor The executor to wrap
     * @return An executor propagating MDC context to every task it runs
     */
    public static ScheduledExecutorService wrap(ScheduledExecutorService executor) {
        return new MdcPropagatingScheduledExecutorService(executor);
    }

    /**
     * Wraps the given executor so every task it runs does so with a copy of the MDC context map of the
     * thread that submitted it, captured at submission time.
     * <p>
     * This is the mechanism to reach for with {@link java.util.concurrent.CompletableFuture}, whose
     * {@code *Async} methods all accept an {@link Executor}: passing a wrapped one to every stage of a
     * chain carries the context along the whole chain on its own, because each stage runs with the
     * context restored and therefore submits the next one from a thread that already has it.
     * <pre>{@code
     * Executor executor = MdcPropagation.wrap(pool);
     * CompletableFuture.supplyAsync(() -> load(id), executor)
     *         .thenApplyAsync(this::render, executor)
     *         .thenAcceptAsync(response::resume, executor);
     * }</pre>
     * Note that the {@code *Async} methods taking no executor run on the common {@link java.util.concurrent.ForkJoinPool}
     * instead, which cannot be wrapped: use the overloads taking one, as above, or wrap the individual
     * stage functions (see {@link #wrapSupplier}, {@link #wrapFunction} and their siblings).
     *
     * @param executor The executor to wrap
     * @return An executor propagating MDC context to every task it runs
     */
    public static Executor wrap(Executor executor) {
        return task -> executor.execute(wrap(task));
    }

    /**
     * Wraps the given supplier so it runs with a copy of the calling thread's current MDC context map,
     * captured at the time this method is called (not when the returned supplier is eventually run), for
     * {@link java.util.concurrent.CompletableFuture#supplyAsync(Supplier)}.
     * <p>
     * Named rather than being another {@code wrap} overload, like the four below: {@link Supplier} has
     * the same shape as {@link Callable}, and {@link Function} the same as {@link Consumer}, so an
     * overload for each would make {@code wrap(() -> value)} ambiguous at every existing call site
     * instead of resolving to the one meant.
     *
     * @param task The supplier to wrap
     * @param <T>  The type of the result returned by the supplier
     * @return A supplier running the given one with the calling thread's MDC context map applied
     */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
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
    public static <T, R> Function<T, R> wrapFunction(Function<T, R> task) {
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
    public static <T> Consumer<T> wrapConsumer(Consumer<T> task) {
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
    public static <T, U, R> BiFunction<T, U, R> wrapBiFunction(BiFunction<T, U, R> task) {
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
    public static <T, U> BiConsumer<T, U> wrapBiConsumer(BiConsumer<T, U> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return (first, second) -> inContext(context, () -> {
            task.accept(first, second);
            return null;
        });
    }

    /**
     * Wraps the given asynchronous response so the request is completed with a copy of the MDC context
     * map of the thread that called this method, whichever thread eventually completes it.
     * <p>
     * A resource method taking a {@code @Suspended AsyncResponse} returns before the response exists, and
     * the container only runs the response filters and writes the entity when {@code resume} is called -
     * on whatever thread the application calls it from. Without this, that thread has none of the MDC
     * this library established for the request, so the {@code Processed ...} line lands with no request
     * identifier, no URI and no method, which is the one line those fields exist for.
     * <pre>{@code
     * @GET
     * public void get(@Suspended AsyncResponse response) {
     *     AsyncResponse propagating = MdcPropagation.wrap(response);
     *     pool.execute(() -> propagating.resume(load()));
     * }
     * }</pre>
     * Wrapping the response rather than every hop that leads to it is deliberate: it covers the
     * completion however the application got there - a pool, a {@code CompletableFuture} chain, a
     * callback from a client library - and it also covers the timeout handler, which the container would
     * otherwise invoke with the unwrapped response on a timer thread.
     * <p>
     * Only the methods completing the request apply the context; the others delegate as-is.
     *
     * @param response The asynchronous response to wrap
     * @return An asynchronous response completing the request with the calling thread's MDC context map
     */
    public static AsyncResponse wrap(AsyncResponse response) {
        return new MdcPropagatingAsyncResponse(response, MDC.getCopyOfContextMap());
    }

    /**
     * Runs the given action with the given context map applied, restoring the running thread's own
     * context map once it completes - rather than merging into it, so a pooled thread never leaks one
     * task's MDC entries into the next one it happens to run.
     *
     * @param context The context map to apply while running the action
     * @param action  The action to run
     * @param <T>     The type of the result returned by the action
     * @return The result of the action
     */
    private static <T> T inContext(Map<String, String> context, Supplier<T> action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        setContext(context);
        try {
            return action.get();
        } finally {
            setContext(previous);
        }
    }

    private static void setContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    /**
     * {@link ExecutorService} decorator delegating everything to an underlying executor, except that
     * every task-accepting method wraps its task(s) with {@link MdcPropagation#wrap(Runnable)} or
     * {@link MdcPropagation#wrap(Callable)} first.
     * <p>
     * Package-private (rather than {@code private}) so {@link MdcPropagatingScheduledExecutorService} can
     * extend it and reuse this behavior for the {@link ExecutorService} methods it does not itself override.
     */
    static class MdcPropagatingExecutorService implements ExecutorService {

        protected final ExecutorService delegate;

        MdcPropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(wrap(command));
        }

        @Override
        public Future<?> submit(Runnable task) {
            return delegate.submit(wrap(task));
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return delegate.submit(wrap(task), result);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return delegate.submit(wrap(task));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return delegate.invokeAll(wrapAll(tasks));
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.invokeAll(wrapAll(tasks), timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
            return delegate.invokeAny(wrapAll(tasks));
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.invokeAny(wrapAll(tasks), timeout, unit);
        }

        private <T> List<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
            return tasks.stream()
                    .map(MdcPropagation::wrap)
                    .collect(Collectors.toList());
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

    }

    /**
     * {@link ScheduledExecutorService} decorator extending {@link MdcPropagatingExecutorService} with the
     * scheduling methods {@link ExecutorService} does not have, wrapping their task(s) the same way.
     */
    private static final class MdcPropagatingScheduledExecutorService extends MdcPropagatingExecutorService implements ScheduledExecutorService {

        private final ScheduledExecutorService scheduledDelegate;

        private MdcPropagatingScheduledExecutorService(ScheduledExecutorService delegate) {
            super(delegate);
            this.scheduledDelegate = delegate;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return scheduledDelegate.schedule(wrap(command), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return scheduledDelegate.schedule(wrap(callable), delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return scheduledDelegate.scheduleAtFixedRate(wrap(command), initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return scheduledDelegate.scheduleWithFixedDelay(wrap(command), initialDelay, delay, unit);
        }

    }

    /**
     * {@link AsyncResponse} decorator delegating everything to an underlying response, except that the
     * methods completing the request apply the MDC context map captured when it was wrapped, so the
     * container runs its response filters - and this library its completion - with the context of the
     * request being completed rather than with whatever the completing thread happened to carry.
     */
    private record MdcPropagatingAsyncResponse(AsyncResponse delegate, Map<String, String> context) implements AsyncResponse {

        @Override
        public boolean resume(Object response) {
            return inContext(context, () -> delegate.resume(response));
        }

        @Override
        public boolean resume(Throwable response) {
            return inContext(context, () -> delegate.resume(response));
        }

        @Override
        public boolean cancel() {
            return inContext(context, delegate::cancel);
        }

        @Override
        public boolean cancel(int retryAfter) {
            return inContext(context, () -> delegate.cancel(retryAfter));
        }

        @Override
        public boolean cancel(Date retryAfter) {
            return inContext(context, () -> delegate.cancel(retryAfter));
        }

        @Override
        public void setTimeoutHandler(TimeoutHandler handler) {
            // Handed this wrapper rather than the response the container passes, so a handler completing
            // the request on the timer thread does so with the context too
            delegate.setTimeoutHandler(ignored -> inContext(context, () -> {
                handler.handleTimeout(this);
                return null;
            }));
        }

        @Override
        public boolean isSuspended() {
            return delegate.isSuspended();
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public boolean setTimeout(long time, TimeUnit unit) {
            return delegate.setTimeout(time, unit);
        }

        @Override
        public Collection<Class<?>> register(Class<?> callback) {
            return delegate.register(callback);
        }

        @Override
        public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
            return delegate.register(callback, callbacks);
        }

        @Override
        public Collection<Class<?>> register(Object callback) {
            return delegate.register(callback);
        }

        @Override
        public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
            return delegate.register(callback, callbacks);
        }

    }

}
