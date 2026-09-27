package com.chavaillaz.jakarta.rs.capture;

import static com.chavaillaz.jakarta.rs.capture.CaptureBuffer.MAX_CAPACITY;
import static jakarta.ws.rs.core.MediaType.CHARSET_PARAMETER;
import static java.lang.Math.ceil;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.nio.charset.CodingErrorAction.REPLACE;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.ws.rs.core.MediaType;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

/**
 * Default {@link LoggedBodyCapture}, capturing at most a given number of bytes in memory.
 * <p>
 * A text body is decoded with the charset of its media type, UTF-8 when it declares none (see
 * {@link #charsetOf(MediaType)}), and any other body is rendered as lowercase hexadecimal (see
 * {@link #isBinary(MediaType)}), as a binary payload decoded as text reads as replacement characters.
 * <p>
 * A body the limit cut short ends with {@link #TRUNCATION_MARKER}. A body a {@link LoggedBodyFilter} fails on -
 * a {@link StackOverflowError} included, as a regular expression gives up on a payload too large for it - is
 * replaced with {@link #FILTERING_FAILURE_MARKER}, its redaction not having happened, and the failure reported.
 */
public class BoundedBodyCapture implements LoggedBodyCapture {

    /**
     * Logger reporting a body filter that failed.
     */
    protected static final Logger log = LoggerFactory.getLogger(BoundedBodyCapture.class);

    /**
     * Appended to a body the limit cut short, so it is not mistaken for a complete, malformed, one.
     */
    public static final String TRUNCATION_MARKER = "...[truncated]";

    /**
     * Written in place of a body a {@link LoggedBodyFilter} failed on: a filter that threw has not finished
     * redacting, so the body must not be logged.
     */
    public static final String FILTERING_FAILURE_MARKER = "[body dropped: a filter failed]";

    /**
     * The {@code application} subtypes carrying text, in lower case, those with a structured syntax suffix apart
     * (see {@link #isTextualApplicationSubtype(String)}): {@code text/*} is textual by definition, and the other
     * types are not.
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
     * Where the captured bytes are kept, and the sink the entity stream is teed to.
     */
    private final CaptureBuffer buffer;

    /**
     * Creates a new bounded, in-memory body capture.
     *
     * @param limit The maximum number of bytes to capture, or {@link LoggedBodyCapture#NO_LIMIT} for no limit
     * @throws IllegalArgumentException if the limit is lower than {@link LoggedBodyCapture#NO_LIMIT}
     */
    public BoundedBodyCapture(int limit) {
        this.buffer = new CaptureBuffer(limit);
    }

    @Override
    public OutputStream sink() {
        return buffer;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The body is decoded once, from the array it was captured in, and copied by the filters changing it alone
     * (see {@link LoggedBodyFilter#apply(CharSequence)}).
     */
    @Override
    public String content(List<LoggedBodyFilter> filters, @Nullable MediaType mediaType) {
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
            // StackOverflowError is the one Error caught: how java.util.regex fails on a payload too large for its
            // pattern, leaving nothing behind once unwound
            log.error("A body filter failed, the body is dropped rather than logged unfiltered", e);
            return FILTERING_FAILURE_MARKER;
        }

        // Appended after filtering, so no filter sees it as part of the payload
        String rendered = body.toString();
        return truncated && !rendered.isEmpty() ? rendered + TRUNCATION_MARKER : rendered;
    }

    /**
     * Gets the charset a text body of the given media type is encoded with: the one its {@code charset}
     * parameter declares, or UTF-8, the default of JAX-RS, when it declares none or one this JVM lacks.
     *
     * @param mediaType The media type of the captured body, or {@code null} if unknown
     * @return The charset to decode the body with
     */
    protected static Charset charsetOf(@Nullable MediaType mediaType) {
        String name = mediaType == null ? null : mediaType.getParameters().get(CHARSET_PARAMETER);
        return name == null ? UTF_8 : Charset.forName(name, UTF_8);
    }

    /**
     * Decodes the given bytes as text, leaving out a character the limit cut in half rather than decoding it to
     * a replacement character: a single-byte charset cuts none, UTF-8 tells from its last bytes where its last
     * character starts (see {@link #completeUtf8Length(byte[], int)}), and any other charset is decoded as if more
     * input were to follow, which stops a decoder in front of an incomplete sequence.
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
                .onMalformedInput(REPLACE)
                .onUnmappableCharacter(REPLACE);
        // Sized for the most characters the bytes can decode to, so decoding never has to stop and grow it
        CharBuffer decoded = CharBuffer.allocate((int) min(MAX_CAPACITY,
                (long) ceil(size * (double) decoder.maxCharsPerByte())));
        decoder.decode(ByteBuffer.wrap(bytes, 0, size), decoded, false);
        return decoded.flip();
    }

    /**
     * Gets how many of the given UTF-8 bytes form complete characters, from the last byte starting a character,
     * which tells how many bytes it takes. Bytes that are not valid UTF-8 are left for the decoding to replace.
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
     * Indicates whether a body of the given media type is binary, rendered as hexadecimal: any but a
     * {@code text/*} type, the {@code application} subtypes carrying text (see {@link #TEXTUAL_APPLICATION_SUBTYPES}),
     * and those with a {@code +json}, {@code +xml} or {@code +yaml} suffix. A missing media type is textual.
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
