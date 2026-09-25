package com.chavaillaz.jakarta.rs;

import java.io.OutputStream;
import java.util.Set;

import jakarta.ws.rs.core.MediaType;

/**
 * Captures a request or response body as it flows through a stream, to later expose it (optionally
 * filtered) as text.
 * <p>
 * An instance is meant to be used once, for a single request or response body: {@link #sink()} receives a
 * copy of the body while it is being read or written, {@link #content(Set)} is called once that is done to
 * retrieve what was captured, and {@link #close()} releases whatever the capture held.
 * <p>
 * This is the extension point for the mechanics of body capture itself (bounded in-memory buffering by
 * default, see {@link BoundedLoggedBodyCapture}), as opposed to {@link LoggedBodyFilter}, which only
 * transforms content already captured. Pass one to {@link LoggedFilterConfiguration.Builder#bodyCapture}, or
 * override {@code createBodyCapture(int)} on {@link LoggedClientFilter}, to plug in a different strategy,
 * for example spilling very large bodies to a temporary file, or capturing a digest of the content.
 */
public interface LoggedBodyCapture extends AutoCloseable {

    /**
     * Limit meaning that a capture keeps the whole body, however large, see {@link LoggedBody#limit()}.
     */
    int NO_LIMIT = -1;

    /**
     * Gets the output stream receiving a copy of every byte of the request or response body, once each, as
     * it is read or written.
     * <p>
     * A sink that throws does not fail the exchange it observes: the providers report the failure, stop
     * writing to the sink, and leave the body out of the logs rather than logging the part of it captured
     * before the failure as if it were the whole.
     *
     * @return The output stream capturing the body
     */
    OutputStream sink();

    /**
     * Gets the captured content, decoded as text and filtered by the given filters.
     * Must be called only once the stream wrapping {@link #sink()} has been fully read or written.
     *
     * @param filters The filters to apply to the captured content
     * @return The captured (and filtered) content
     */
    String content(Set<LoggedBodyFilter> filters);

    /**
     * Gets the captured content, filtered by the given filters, using the given media type to decide how
     * to render it as text (see {@link BoundedLoggedBodyCapture} for the default rule).
     * Must be called only once the stream wrapping {@link #sink()} has been fully read or written.
     * <p>
     * Defaults to ignoring the media type, delegating to {@link #content(Set)}.
     *
     * @param filters   The filters to apply to the captured content
     * @param mediaType The media type of the captured request or response body, or {@code null} if unknown
     * @return The captured (and filtered) content
     */
    default String content(Set<LoggedBodyFilter> filters, MediaType mediaType) {
        return content(filters);
    }

    /**
     * Releases whatever this capture holds, the exchange being done with it: a temporary file a large body
     * was spilled to, a buffer taken from a pool.
     * <p>
     * Called exactly once per capture created, by the provider that created it, whatever happened: after the
     * content has been read, after reading it failed, or after the entity stream could not be wrapped at all.
     * Whatever it throws is reported and swallowed like every other failure of logging, hence no checked
     * exception. Does nothing by default, which suits a capture holding nothing but memory.
     */
    @Override
    default void close() {
        // Nothing to release, see above
    }

}
