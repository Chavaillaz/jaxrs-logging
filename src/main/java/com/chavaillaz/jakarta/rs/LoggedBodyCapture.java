package com.chavaillaz.jakarta.rs;

import java.io.OutputStream;
import java.util.Set;

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

}
