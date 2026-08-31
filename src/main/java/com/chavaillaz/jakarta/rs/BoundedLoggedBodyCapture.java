package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.BoundedOutputStream.NO_LIMIT;
import static com.chavaillaz.jakarta.rs.BoundedOutputStream.trimIncompleteTrailingCharacter;
import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.HexFormat;
import java.util.Set;

import jakarta.ws.rs.core.MediaType;

/**
 * Default {@link LoggedBodyCapture} implementation, capturing at most a configured number of bytes in
 * memory.
 * <p>
 * A body whose {@link MediaType} is not text-based (see {@link #isBinary(MediaType)}) is rendered as a
 * lowercase hexadecimal string instead of being decoded as UTF-8 text: an arbitrary binary payload (an
 * uploaded image, a protobuf message, ...) has no reason to be valid UTF-8, so decoding it as such would
 * produce a log line full of replacement characters instead of anything usable for troubleshooting.
 * <p>
 * When the limit actually dropped bytes, the rendered content ends with {@link #TRUNCATION_MARKER}: a
 * body silently cut at the limit otherwise reads, in the logs, as a complete (and often syntactically
 * broken) payload, which is exactly the kind of thing someone troubleshooting from those logs will
 * misread as the application having sent malformed content.
 */
public class BoundedLoggedBodyCapture implements LoggedBodyCapture {

    /**
     * Appended to the rendered content when the configured limit dropped part of the body.
     */
    public static final String TRUNCATION_MARKER = "...[truncated]";

    /**
     * Initial capacity of the buffer, also used as its upper bound when a limit is configured, so a
     * small body does not pay for a buffer sized after a generous limit, and a large one does not pay
     * for the repeated array copies of a buffer growing from scratch.
     */
    protected static final int INITIAL_BUFFER_SIZE = 1024;

    protected final ByteArrayOutputStream buffer;
    protected final BoundedOutputStream sink;

    /**
     * Creates a new bounded, in-memory body capture.
     *
     * @param limit The maximum number of bytes to capture, or {@link BoundedOutputStream#NO_LIMIT} for no limit
     */
    public BoundedLoggedBodyCapture(int limit) {
        this.buffer = new ByteArrayOutputStream(limit == NO_LIMIT ? INITIAL_BUFFER_SIZE : min(limit, INITIAL_BUFFER_SIZE));
        this.sink = new BoundedOutputStream(buffer, limit);
    }

    @Override
    public OutputStream sink() {
        return sink;
    }

    @Override
    public String content(Set<LoggedBodyFilter> filters) {
        // No media type available: keep the historical behavior of always decoding as UTF-8 text
        return content(filters, null);
    }

    @Override
    public String content(Set<LoggedBodyFilter> filters, MediaType mediaType) {
        byte[] bytes = buffer.toByteArray();
        boolean truncated = sink.isTruncated();
        String body;

        if (isBinary(mediaType)) {
            body = HexFormat.of().formatHex(bytes);
        } else {
            if (truncated) {
                // A limit reached mid-character would otherwise decode to a trailing replacement character
                bytes = trimIncompleteTrailingCharacter(bytes);
            }
            body = new String(bytes, UTF_8);
        }

        if (!filters.isEmpty()) {
            StringBuilder bodyBuilder = new StringBuilder(body);
            filters.forEach(filter -> filter.filter(bodyBuilder));
            body = bodyBuilder.toString();
        }

        // Appended after filtering, so a filter neither has to preserve the marker nor can mistake it
        // for part of the payload it is inspecting
        return truncated && !body.isEmpty() ? body + TRUNCATION_MARKER : body;
    }

    /**
     * Indicates whether a body of the given media type should be treated as binary (and thus rendered
     * as hexadecimal rather than decoded as UTF-8 text).
     * <p>
     * Textual: any {@code text/*} type, and the {@code application} subtypes commonly used for
     * structured or form text ({@code json}, {@code xml}, {@code javascript}, {@code x-www-form-urlencoded},
     * and any {@code +json}/{@code +xml} structured syntax suffix, e.g. {@code application/hal+json}).
     * Binary: everything else, notably {@code application/octet-stream}, {@code application/pdf},
     * {@code application/protobuf}, {@code image/*}, {@code audio/*}, {@code video/*} and {@code multipart/*}.
     * A missing media type is treated as textual, to keep the historical behavior of this class where
     * none is known.
     *
     * @param mediaType The media type of the captured body, or {@code null} if unknown
     * @return {@code true} if the body should be treated as binary, {@code false} otherwise
     */
    protected static boolean isBinary(MediaType mediaType) {
        if (mediaType == null) {
            return false;
        } else if ("text".equalsIgnoreCase(mediaType.getType())) {
            return false;
        } else if (!"application".equalsIgnoreCase(mediaType.getType())) {
            return true;
        }

        String subtype = mediaType.getSubtype();
        return !("json".equalsIgnoreCase(subtype)
                || "xml".equalsIgnoreCase(subtype)
                || "javascript".equalsIgnoreCase(subtype)
                || "x-www-form-urlencoded".equalsIgnoreCase(subtype)
                || subtype.toLowerCase().endsWith("+json")
                || subtype.toLowerCase().endsWith("+xml"));
    }

}
