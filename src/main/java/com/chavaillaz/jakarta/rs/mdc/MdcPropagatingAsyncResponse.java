package com.chavaillaz.jakarta.rs.mdc;

import static com.chavaillaz.jakarta.rs.mdc.MdcPropagation.inContext;

import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * {@link AsyncResponse} decorator delegating everything to an underlying response, except that the
 * methods completing the request apply the MDC context map captured when it was wrapped, so the
 * container runs its response filters - and this library its completion - with the context of the
 * request being completed rather than with whatever the completing thread happened to carry.
 */
record MdcPropagatingAsyncResponse(AsyncResponse delegate, @Nullable Map<String, String> context) implements AsyncResponse {

    @Override
    public boolean resume(@Nullable Object response) {
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
    public void setTimeoutHandler(@Nullable TimeoutHandler handler) {
        if (handler == null) {
            // Clearing the handler lets the container time the request out its own way again: wrapped,
            // it became a handler failing on the very timeout it was meant to leave alone
            delegate.setTimeoutHandler(null);
            return;
        }
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
