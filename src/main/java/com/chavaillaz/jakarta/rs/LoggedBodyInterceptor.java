package com.chavaillaz.jakarta.rs;

import static jakarta.ws.rs.RuntimeType.SERVER;

import java.io.IOException;

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

/**
 * Captures the request and response bodies logged by {@link LoggedFilter}, from a position in the
 * interceptor chain where they are readable.
 * <p>
 * Interceptors are invoked in ascending priority order, and each one wraps the stream for those that
 * run after it. The interceptor that runs <em>first</em> therefore sits closest to the network, and an
 * entity coder ({@link Priorities#ENTITY_CODER}) registered after it compresses on the way out and
 * decompresses on the way in, between that interceptor and the entity itself. Capturing from
 * {@link LoggedFilter}'s own priority ({@link Priorities#HEADER_DECORATOR}, deliberately low so its
 * <em>filters</em> run early on the request and late on the response) therefore captured the compressed
 * bytes in both directions: a {@code Content-Encoding: gzip} request or response was logged as gzip
 * noise rather than as the payload the application actually read or produced.
 * <p>
 * This provider runs after the entity coder instead, so what it captures is the entity's own
 * representation, whatever transfer encoding was applied around it. Everything else - deciding whether
 * a body must be captured at all, applying {@link LoggedBodyFilter}s, and writing the log lines - stays
 * on {@link LoggedFilter}, which this provider simply calls back into. That keeps the capture its
 * configuration sets up in control, and keeps the completion of a request
 * (the "Processed ..." line and the MDC cleanup) owned by a provider that is always invoked: an
 * application that registers its providers explicitly and forgets this one loses the body from its logs,
 * not the log line itself.
 *
 * @see LoggedFilter#captureRequestBody(ReaderInterceptorContext)
 * @see LoggedFilter#captureResponseBody(WriterInterceptorContext)
 */
@Logged
@Provider
@ConstrainedTo(SERVER)
@Priority(Priorities.ENTITY_CODER + 100)
public class LoggedBodyInterceptor implements ReaderInterceptor, WriterInterceptor {

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
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
     * {@link LoggedFilter#filter(jakarta.ws.rs.container.ContainerRequestContext)}.
     * <p>
     * Reading it from the request rather than injecting it is what lets this provider hand the capture
     * back to the exact instance - a subclass, possibly one of several registered - that is handling
     * this request. Its absence simply means no {@link LoggedFilter} is active here (the resource is not
     * annotated, or this provider was registered without it), in which case there is nothing to capture.
     *
     * @param context The context of the entity being read or written
     * @return The filter handling the current request, or {@code null} if there is none
     */
    protected LoggedFilter getFilter(InterceptorContext context) {
        LoggedRequestState state = LoggedRequestState.find(context);
        return state == null ? null : state.getProvider();
    }

}
