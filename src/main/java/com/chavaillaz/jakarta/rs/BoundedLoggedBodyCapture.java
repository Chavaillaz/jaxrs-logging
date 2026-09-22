package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.BoundedOutputStream.NO_LIMIT;
import static com.chavaillaz.jakarta.rs.BoundedOutputStream.trimIncompleteTrailingCharacter;
import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link LoggedBodyCapture} implementation, capturing at most a configured number of bytes in
 * memory.
 * <p>
 * A body whose {@link MediaType} is not text-based (see {@link #isBinary(MediaType)}) is rendered as a
 * lowercase hexadecimal string instead of being decoded as UTF-8 text: an arbitrary binary payload (an
 * uploaded image, a protobuf message, ...) has no reason to be valid UTF-8, so decoding it as such would
 * produce a log line full of replacement characters instead of anything usable for troubleshooting.
 * <p>
 * A {@link LoggedBodyFilter} that throws is not allowed to leak the body it was meant to redact: the
 * whole content is replaced with {@link #FILTERING_FAILURE_MARKER}, as the only thing known for certain
 * at that point is that the redaction the application asked for did not happen. Nor is it allowed to
 * break the exchange, which is why the failure is logged and swallowed rather than propagated back into
 * the entity stream this capture is teeing - including a {@link StackOverflowError}, the way a regular
 * expression gives up on a payload too large for it.
 * <p>
 * When the limit actually dropped bytes, the rendered content ends with {@link #TRUNCATION_MARKER}: a
 * body silently cut at the limit otherwise reads, in the logs, as a complete (and often syntactically
 * broken) payload, which is exactly the kind of thing someone troubleshooting from those logs will
 * misread as the application having sent malformed content.
 */
public class BoundedLoggedBodyCapture implements LoggedBodyCapture {

    protected static final Logger log = LoggerFactory.getLogger(BoundedLoggedBodyCapture.class);

    /**
     * Appended to the rendered content when the configured limit dropped part of the body.
     */
    public static final String TRUNCATION_MARKER = "...[truncated]";

    /**
     * Written in place of the whole body when a {@link LoggedBodyFilter} failed on it.
     * <p>
     * The body is dropped rather than logged as captured, because a filter is a "this must never reach
     * the logs" instruction: a filter that threw has, by definition, not finished redacting, so what it
     * was working on is exactly what must not be written. Dropping it costs one unreadable log line;
     * logging it costs a credential in a log aggregator.
     */
    public static final String FILTERING_FAILURE_MARKER = "[body dropped: a filter failed]";

    /**
     * The {@code application} subtypes that carry text rather than bytes, in lower case.
     * <p>
     * The {@code application} type is the only one needing a list: {@code text/*} is textual by
     * definition, and everything else ({@code image}, {@code audio}, {@code video}, {@code multipart})
     * is not. Subtypes ending in a structured syntax suffix are recognized separately, see
     * {@link #isTextualApplicationSubtype(String)}.
     */
    protected static final Set<String> TEXTUAL_APPLICATION_SUBTYPES = Set.of(
            "json",
            "xml",
            "javascript",
            "x-www-form-urlencoded");

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
        // Every negative limit, not only NO_LIMIT, sizes the buffer the default way, so that validating
        // the limit is left entirely to the sink below: sizing on a limit of -2 otherwise rejected it
        // first, with a "Negative initial size" nobody can trace back to the annotation to fix
        this.buffer = new ByteArrayOutputStream(limit < 0 ? INITIAL_BUFFER_SIZE : min(limit, INITIAL_BUFFER_SIZE));
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
            try {
                filters.forEach(filter -> filter.filter(bodyBuilder));
            } catch (Exception | StackOverflowError e) {
                // StackOverflowError is the one Error caught: it is how java.util.regex fails on a payload
                // large enough for the pattern it runs, filters are regular expressions more often than not,
                // and nothing is left behind once it has unwound. Letting it out instead would fail the
                // exchange this capture is only observing, which no Exception thrown here is allowed to do.
                log.error("A body filter failed, the body is dropped rather than logged unfiltered", e);
                return FILTERING_FAILURE_MARKER;
            }
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
        if (mediaType == null || "text".equalsIgnoreCase(mediaType.getType())) {
            return false;
        } else if (!"application".equalsIgnoreCase(mediaType.getType())) {
            return true;
        }
        return !isTextualApplicationSubtype(mediaType.getSubtype().toLowerCase(Locale.ROOT));
    }

    /**
     * Indicates whether the given {@code application} subtype is one of those carrying text.
     *
     * @param subtype The subtype to check, already lower cased
     * @return {@code true} if a body of that subtype is text, {@code false} otherwise
     */
    private static boolean isTextualApplicationSubtype(String subtype) {
        // A structured syntax suffix (RFC 6838) is what makes a vendor-specific type readable without
        // knowing the vendor: application/hal+json and application/vnd.acme.order+xml are text, whatever
        // precedes the suffix
        return TEXTUAL_APPLICATION_SUBTYPES.contains(subtype)
                || subtype.endsWith("+json")
                || subtype.endsWith("+xml");
    }

}
