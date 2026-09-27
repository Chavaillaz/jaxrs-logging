package com.chavaillaz.jakarta.rs.capture;

import jakarta.ws.rs.core.MediaType;
import java.io.OutputStream;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.rs.LoggedBody;
import com.chavaillaz.jakarta.rs.LoggedFeatureConfiguration;
import com.chavaillaz.jakarta.rs.client.LoggedClientFeature;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Captures a request or response body as it is read or written, and renders it as text once done.
 * <p>
 * An instance captures a single body: {@link #getSink()} receives a copy of it, {@link #getContent(List, MediaType)}
 * renders it once the entity is done - for a body read as a stream, once the stream ends, possibly on another
 * thread - and {@link #close()} releases what the capture holds.
 * <p>
 * The default one captures in memory, up to a limit (see {@link BoundedBodyCapture}), and
 * {@link LoggedBodyFilter} transforms what was captured. Plug in another one - spilling large bodies to a
 * temporary file, say - with {@link LoggedFeatureConfiguration.Builder#bodyCapture}, or
 * {@link LoggedClientFeature.Builder#bodyCapture} for the calls made.
 */
public interface LoggedBodyCapture extends AutoCloseable {

    /**
     * Limit meaning that a capture keeps the whole body, however large, see {@link LoggedBody#limit()}.
     */
    int NO_LIMIT = -1;

    /**
     * Limit a body is captured with when none is configured, 64 KiB: enough for the payloads of most APIs,
     * while a large one, or one a client makes large, costs no more than that in memory and in the logs.
     */
    int DEFAULT_LIMIT = 64 * 1024;

    /**
     * Checks the given limit, which is valid when it is {@link #NO_LIMIT} or a number of bytes.
     *
     * @param limit The limit to check
     * @return The limit, valid
     * @throws IllegalArgumentException if the limit is lower than {@link #NO_LIMIT}
     */
    static int checkLimit(int limit) {
        if (limit < NO_LIMIT) {
            throw new IllegalArgumentException("Limit must be -1 (no limit) or a number of bytes, 0 included, but was " + limit);
        }
        return limit;
    }

    /**
     * Gets the stream receiving a copy of each byte of the body as it is read or written. A sink failing fails
     * the capture alone: the failure is reported, and the body left out of the logs rather than logged in part.
     *
     * @return The output stream capturing the body
     */
    OutputStream getSink();

    /**
     * Gets the captured content, rendered as the given media type says (see {@link BoundedBodyCapture})
     * and filtered by the given filters in their order, once the stream wrapping {@link #getSink()} was fully read
     * or written.
     *
     * @param filters   The filters to apply to the captured content, in the order they apply in
     * @param mediaType The media type of the captured request or response body, or {@code null} if unknown
     * @return The captured (and filtered) content, or {@code null} to leave the body out of the logs
     */
    @Nullable String getContent(List<LoggedBodyFilter> filters, @Nullable MediaType mediaType);

    /**
     * Releases what this capture holds - a temporary file, a pooled buffer - once per capture, whatever happened,
     * but for a client response read as a stream the calling code neither reads to its end nor closes. What it
     * throws is reported and swallowed, like any failure of logging. Does nothing by default.
     */
    @Override
    default void close() {
        // Nothing to release, see above
    }

}
