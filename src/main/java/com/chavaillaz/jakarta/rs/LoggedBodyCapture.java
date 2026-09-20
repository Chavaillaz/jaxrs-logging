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
 * read or written, {@link #content(Set)} is called once that is done to retrieve what was captured, and
 * {@link #close()} releases whatever the capture held.
 * <p>
 * This is the extension point for the mechanics of body capture itself (bounded in-memory buffering by
 * default, see {@link BoundedLoggedBodyCapture}), as opposed to {@link LoggedBodyFilter}, which only
 * transforms content already captured. Override {@code createBodyCapture(int)} on {@link LoggedFilter}
 * (or {@link LoggedClientFilter}) to plug in a different strategy, for example spilling very large
 * bodies to a temporary file instead of memory, or capturing a digest instead of the full content.
 */
public interface LoggedBodyCapture extends AutoCloseable {

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

    /**
     * Releases whatever this capture holds, the exchange being done with it.
     * <p>
     * Called exactly once per capture that was put in place, by the provider that created it, and called
     * whatever happened before: after the content has been read, after reading it failed, and after the
     * entity stream could not be wrapped in the first place. A capture is therefore never left holding
     * anything, and never has to guess from a partially used instance whether it still owns something.
     * <p>
     * Defaults to doing nothing, which is what the in-memory capture shipped with this library needs:
     * its buffer is released by the garbage collector along with the capture itself, and an existing
     * implementation of this interface holding no more than that keeps compiling and behaving exactly as
     * before. It is the other kind - one spilling a large body to a temporary file, streaming it to
     * another process, taking anything from a pool - that this exists for: those hold a resource the
     * garbage collector does not reclaim, and this is where they give it back.
     * <p>
     * Declared without the checked exception {@link AutoCloseable} allows, deliberately: releasing a
     * capture is cleanup of the logging path, and a caller forced to handle a checked exception around
     * it would be handling something it has no answer to anyway. Whatever an implementation throws here
     * is reported and swallowed by the provider, like every other failure of this path, so releasing a
     * capture cannot be the reason an exchange fails either.
     */
    @Override
    default void close() {
        // Nothing to release, see above
    }

}
