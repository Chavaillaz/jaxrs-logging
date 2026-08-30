package com.chavaillaz.jakarta.rs;

import java.io.OutputStream;
import java.util.Set;

import jakarta.ws.rs.core.MediaType;

/**
 * Captures a request or response body as it flows through a stream, to later expose it (optionally
 * filtered) as text.
 * <p>
 * An instance is meant to be used once, for a single request or response body: {@link #sink()} is used
 * as the target of a {@code TeeInputStream}/{@code TeeOutputStream} wrapping the body while it is being
 * read or written, and {@link #content(Set)} is called once that is done to retrieve what was captured.
 * <p>
 * This is the extension point for the mechanics of body capture itself (bounded in-memory buffering by
 * default, see {@link BoundedLoggedBodyCapture}), as opposed to {@link LoggedBodyFilter}, which only
 * transforms content already captured. Override {@code createBodyCapture(int)} on {@link LoggedFilter}
 * (or {@link LoggedClientFilter}) to plug in a different strategy, for example spilling very large
 * bodies to a temporary file instead of memory, or capturing a digest instead of the full content.
 */
public interface LoggedBodyCapture {

    /**
     * Gets the output stream acting as the sink of a {@code TeeInputStream}/{@code TeeOutputStream}
     * wrapping the request or response body, capturing a copy of every byte read from or written to it.
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
     * Defaults to ignoring the media type and delegating to {@link #content(Set)}, so an existing
     * implementation of this interface only needs to override this method to react to it; it does not
     * need any change to keep compiling and behaving exactly as before.
     *
     * @param filters   The filters to apply to the captured content
     * @param mediaType The media type of the captured request or response body, or {@code null} if unknown
     * @return The captured (and filtered) content
     */
    default String content(Set<LoggedBodyFilter> filters, MediaType mediaType) {
        return content(filters);
    }

}
