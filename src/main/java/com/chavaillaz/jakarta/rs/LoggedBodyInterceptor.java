package com.chavaillaz.jakarta.rs;

import static jakarta.ws.rs.Priorities.ENTITY_CODER;
import static jakarta.ws.rs.RuntimeType.SERVER;

import jakarta.annotation.Priority;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.InterceptorContext;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import java.io.IOException;

import org.jspecify.annotations.Nullable;

/**
 * Captures the request and response bodies logged by {@link LoggedFilter}, from a position in the
 * interceptor chain where they are readable.
 * <p>
 * Interceptors run in ascending priority order, each wrapping the stream for the ones after it, so an entity
 * coder ({@link Priorities#ENTITY_CODER}) sits between the interceptors running before it and the entity:
 * from the priority its filters need, {@link Priorities#HEADER_DECORATOR}, which runs it before the coder,
 * {@link LoggedFilter} would capture the compressed bytes of a {@code Content-Encoding: gzip} body. This
 * provider runs after the coder, and captures the entity itself.
 * <p>
 * Every decision about a body - whether it is captured, how it is filtered, where it is logged - stays with
 * the {@link LoggedFilter} handling the request, which this provider calls back. An application registering
 * its providers explicitly and forgetting this one therefore loses the bodies, not the log lines.
 */
@Logged
@Provider
@ConstrainedTo(SERVER)
@Priority(ENTITY_CODER + 100)
public class LoggedBodyInterceptor implements ReaderInterceptor, WriterInterceptor {

    /**
     * Creates the interceptor, which the container does through this no-argument constructor.
     */
    public LoggedBodyInterceptor() {
        // Nothing to set up: every decision is taken by the LoggedFilter handling the request
    }

    @Override
    public @Nullable Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        LoggedFilter filter = getFilter(context);
        return filter == null ? context.proceed() : filter.captureRequestBody(context);
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        LoggedFilter filter = getFilter(context);
        if (filter == null) {
            context.proceed();
        } else {
            filter.captureResponseBody(context);
        }
    }

    /**
     * Gets the {@link LoggedFilter} instance handling the current request, as recorded on the request by
     * {@link LoggedFilter#filter(jakarta.ws.rs.container.ContainerRequestContext)}: read from the request
     * rather than injected, as several of them, subclasses configured their own way, can be registered.
     * <p>
     * There is none when no {@link LoggedFilter} is active for the request, which leaves nothing to capture.
     *
     * @param context The context of the entity being read or written
     * @return The filter handling the current request, or {@code null} if there is none
     */
    protected @Nullable LoggedFilter getFilter(InterceptorContext context) {
        LoggedRequestState state = LoggedRequestState.find(context);
        return state == null ? null : state.getProvider();
    }

}
