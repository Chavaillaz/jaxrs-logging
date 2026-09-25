package com.chavaillaz.jakarta.rs.capture;

import static java.lang.Math.ceil;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.ws.rs.core.MediaType;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Default {@link LoggedBodyCapture} implementation, capturing at most a configured number of bytes in
 * memory.
 * <p>
 * A text body is decoded with the charset its media type declares, and as UTF-8 when it declares none (see
 * {@link #charsetOf(MediaType)}). A body whose media type is not text-based (see {@link #isBinary(MediaType)})
 * is rendered as lowercase hexadecimal instead, as a binary payload decoded as text reads as nothing but
 * replacement characters.
 * <p>
 * A body the limit cut short ends with {@link #TRUNCATION_MARKER}, so it is not mistaken for a complete, and
 * malformed, payload. A body whose {@link LoggedBodyFilter} throws - a {@link StackOverflowError} included,
 * as a regular expression gives up on a payload too large for it - is replaced with
 * {@link #FILTERING_FAILURE_MARKER}: the redaction it was meant to go through did not happen, so it must not
 * be logged, and the failure is reported rather than allowed to fail the exchange.
 */
public class BoundedLoggedBodyCapture implements LoggedBodyCapture {

    /**
     * Logger reporting a body filter that failed.
     */
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
     * <p>
     * Besides the usual structured and form text, the list covers the text formats of streaming and
     * configuration APIs - newline-delimited JSON of a bulk request, JSON text sequences, YAML, GraphQL -
     * which, logged as hexadecimal, are as good as unlogged.
     */
    protected static final Set<String> TEXTUAL_APPLICATION_SUBTYPES = Set.of(
            "json",
            "xml",
            "javascript",
            "x-www-form-urlencoded",
            "x-ndjson",
            "json-seq",
            "yaml",
            "x-yaml",
            "graphql");

    /**
     * The structured syntax suffixes (RFC 6838) of the {@code application} subtypes that carry text, in
     * lower case, see {@link #isTextualApplicationSubtype(String)}.
     */
    protected static final Set<String> TEXTUAL_SUFFIXES = Set.of(
            "+json",
            "+xml",
            "+yaml");

    /**
     * Where the captured bytes are kept, and the sink the entity stream is teed to (see {@link CaptureBuffer}
     * for why it is neither synchronized nor wrapped).
     */
    private final CaptureBuffer buffer;

    /**
     * Creates a new bounded, in-memory body capture.
     *
     * @param limit The maximum number of bytes to capture, or {@link LoggedBodyCapture#NO_LIMIT} for no limit
     * @throws IllegalArgumentException if the limit is lower than {@link LoggedBodyCapture#NO_LIMIT}
     */
    public BoundedLoggedBodyCapture(int limit) {
        this.buffer = new CaptureBuffer(limit);
    }

    @Override
    public OutputStream sink() {
        return buffer;
    }

    @Override
    public String content(Set<LoggedBodyFilter> filters) {
        // Without a media type, the body is decoded as UTF-8 text
        return content(filters, null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The body is copied no more than it has to be, as a large one is copied in full each time: it is
     * decoded once, straight from the array it was captured in, and each filter either hands it back as it
     * is or produces its filtered copy (see {@link LoggedBodyFilter#apply(CharSequence)}), the result being
     * turned into a string once, at the end.
     */
    @Override
    public String content(Set<LoggedBodyFilter> filters, @Nullable MediaType mediaType) {
        byte[] bytes = buffer.array();
        int size = buffer.size();
        boolean truncated = buffer.isTruncated();
        CharSequence body = isBinary(mediaType)
                ? HexFormat.of().formatHex(bytes, 0, size)
                : decode(bytes, size, charsetOf(mediaType), truncated);

        try {
            for (LoggedBodyFilter filter : filters) {
                body = filter.apply(body);
            }
        } catch (Exception | StackOverflowError e) {
            // StackOverflowError is the one Error caught: it is how java.util.regex fails on a payload
            // large enough for the pattern it runs, filters are regular expressions more often than not,
            // and nothing is left behind once it has unwound. Letting it out instead would fail the
            // exchange this capture is only observing, which no Exception thrown here is allowed to do.
            log.error("A body filter failed, the body is dropped rather than logged unfiltered", e);
            return FILTERING_FAILURE_MARKER;
        }

        // Appended after filtering, so a filter neither has to preserve the marker nor can mistake it
        // for part of the payload it is inspecting
        String rendered = body.toString();
        return truncated && !rendered.isEmpty() ? rendered + TRUNCATION_MARKER : rendered;
    }

    /**
     * Gets the charset a text body of the given media type is encoded with: the one its {@code charset}
     * parameter declares, or UTF-8 - the default of JAX-RS itself - when it declares none, or one this JVM
     * does not support.
     *
     * @param mediaType The media type of the captured body, or {@code null} if unknown
     * @return The charset to decode the body with
     */
    protected static Charset charsetOf(@Nullable MediaType mediaType) {
        String name = mediaType == null ? null : mediaType.getParameters().get(MediaType.CHARSET_PARAMETER);
        return name == null ? UTF_8 : Charset.forName(name, UTF_8);
    }

    /**
     * Decodes the given bytes as text, once, straight from the array they were captured in.
     * <p>
     * A body cut short by the limit may end in the middle of a character, which must be left out rather than
     * decoded to a trailing replacement character. How that character is found depends on the charset, and
     * the common ones are spared a decoding pass of their own:
     * <ul>
     *     <li>a single-byte charset has nothing to cut, every byte being a whole character;</li>
     *     <li>UTF-8 tells from the last few bytes alone where its last character starts, and how long it is
     *     (see {@link #completeUtf8Length(byte[], int)});</li>
     *     <li>any other charset is decoded as if more input were to follow, which makes a decoder stop in
     *     front of an incomplete sequence instead of reporting it as malformed - a UTF-16 surrogate pair or a
     *     Shift_JIS double byte alike - and the characters it decoded are the result.</li>
     * </ul>
     *
     * @param bytes     The bytes captured, possibly cut in the middle of a character
     * @param size      The number of bytes captured, at the start of the array
     * @param charset   The charset the bytes are encoded with
     * @param truncated Whether the limit dropped bytes, and may therefore have cut a character in half
     * @return The text the bytes decode to, without any character the limit cut in half
     */
    protected static CharSequence decode(byte[] bytes, int size, Charset charset, boolean truncated) {
        if (!truncated || ISO_8859_1.equals(charset) || US_ASCII.equals(charset)) {
            return new String(bytes, 0, size, charset);
        } else if (UTF_8.equals(charset)) {
            return new String(bytes, 0, completeUtf8Length(bytes, size), UTF_8);
        }

        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        // Sized for the most characters the bytes can decode to, so decoding never has to stop and grow it
        CharBuffer decoded = CharBuffer.allocate((int) min(CaptureBuffer.MAX_CAPACITY,
                (long) ceil(size * (double) decoder.maxCharsPerByte())));
        decoder.decode(ByteBuffer.wrap(bytes, 0, size), decoded, false);
        return decoded.flip();
    }

    /**
     * Gets how many of the given UTF-8 bytes form complete characters, so that a character the limit cut in
     * the middle of can be left out.
     * <p>
     * UTF-8 marks the first byte of each character, and tells from it how many bytes the character takes,
     * so only the last few bytes need to be read: the last one not continuing a character is where the last
     * character starts. Bytes that are not valid UTF-8 are left in place, for the decoding that follows to
     * replace as it would anywhere else in the body.
     *
     * @param bytes The bytes captured, encoded in UTF-8
     * @param size  The number of bytes captured, at the start of the array
     * @return The number of leading bytes forming complete characters
     */
    private static int completeUtf8Length(byte[] bytes, int size) {
        for (int i = size - 1; i >= max(0, size - 4); i--) {
            int lead = bytes[i] & 0xFF;
            if ((lead & 0xC0) != 0x80) {
                int length;
                if (lead >= 0xF8 || lead < 0xC0) {
                    length = 1;
                } else if (lead >= 0xF0) {
                    length = 4;
                } else if (lead >= 0xE0) {
                    length = 3;
                } else {
                    length = 2;
                }
                return i + length > size ? i : size;
            }
        }
        // Nothing but continuation bytes at the end: malformed rather than cut, left for the decoding
        return size;
    }

    /**
     * Indicates whether a body of the given media type should be treated as binary (and thus rendered
     * as hexadecimal rather than decoded as text).
     * <p>
     * Textual: any {@code text/*} type, and the {@code application} subtypes commonly used for
     * structured or form text (see {@link #TEXTUAL_APPLICATION_SUBTYPES}), including any with a
     * {@code +json}, {@code +xml} or {@code +yaml} structured syntax suffix, e.g. {@code application/hal+json}.
     * Binary: everything else, notably {@code application/octet-stream}, {@code application/pdf},
     * {@code application/protobuf}, {@code image/*}, {@code audio/*}, {@code video/*} and {@code multipart/*}.
     * A missing media type is treated as textual.
     *
     * @param mediaType The media type of the captured body, or {@code null} if unknown
     * @return {@code true} if the body should be treated as binary, {@code false} otherwise
     */
    protected static boolean isBinary(@Nullable MediaType mediaType) {
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
        int suffix = subtype.lastIndexOf('+');
        return TEXTUAL_APPLICATION_SUBTYPES.contains(subtype)
                || (suffix >= 0 && TEXTUAL_SUFFIXES.contains(subtype.substring(suffix)));
    }

}
