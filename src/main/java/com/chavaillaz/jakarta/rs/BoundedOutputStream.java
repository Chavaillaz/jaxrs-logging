package com.chavaillaz.jakarta.rs;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Arrays.copyOfRange;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.commons.io.output.ProxyOutputStream;

/**
 * An output stream wrapping another output stream and limiting the number of bytes
 * effectively written into the wrapped output stream.
 * <p>
 * Note that a limit can cut a multi-byte UTF-8 character in half. Use {@link #isTruncated()} and
 * {@link #trimIncompleteTrailingCharacter(byte[])} to strip such a dangling partial character before
 * decoding the captured bytes as text, instead of leaving a replacement character at the end of it.
 */
public class BoundedOutputStream extends ProxyOutputStream {

    /**
     * Value of {@link #limit} meaning that no limit is applied.
     */
    public static final int NO_LIMIT = -1;

    private final int limit;
    private int writtenBytes = 0;
    private boolean truncated = false;

    /**
     * Creates a new bounded output stream.
     *
     * @param out   The output stream to wrap
     * @param limit The maximum number of bytes to write into the wrapped output stream,
     *              or {@link #NO_LIMIT} to write without any limit
     * @throws IllegalArgumentException if the limit is lower than {@link #NO_LIMIT}
     */
    public BoundedOutputStream(OutputStream out, int limit) {
        super(out);
        if (limit < NO_LIMIT) {
            throw new IllegalArgumentException("Limit must be -1 (unlimited) or a positive value, but was " + limit);
        }
        this.limit = limit;
    }

    @Override
    public void write(int b) throws IOException {
        if (limit == NO_LIMIT || writtenBytes < limit) {
            super.write(b);
            // Only tracked when a limit applies, as it would otherwise overflow on a body larger than
            // Integer.MAX_VALUE and, for an unlimited stream, is not used for anything
            if (limit != NO_LIMIT) {
                writtenBytes++;
            }
        } else {
            truncated = true;
        }
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (limit == NO_LIMIT) {
            super.write(b, off, len);
            return;
        }

        int count = min(len, limit - writtenBytes);
        if (count > 0) {
            super.write(b, off, count);
            writtenBytes += count;
        }
        if (count < len) {
            truncated = true;
        }
    }

    /**
     * Indicates whether at least one byte was dropped because the configured limit was reached, meaning
     * the captured content is incomplete and may end with a truncated multi-byte UTF-8 character.
     * <p>
     * Reports truncation only when bytes were <em>actually</em> dropped, not merely when the limit was
     * reached exactly: a body whose size happens to equal the limit is complete, and reporting it as
     * truncated would make the logs claim content is missing when none is.
     *
     * @return {@code true} if content was truncated, {@code false} otherwise
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Trims a dangling, incomplete multi-byte UTF-8 character from the end of the given bytes, if any.
     * <p>
     * Meant to be applied to bytes captured through a stream where {@link #isTruncated()} returned
     * {@code true}, before decoding them as text: a limit reached mid-character otherwise leaves a
     * partial sequence at the end that decodes to a trailing replacement character ({@code �}).
     * <p>
     * Only the last (at most 4) bytes are inspected, as that is the longest a single UTF-8 character
     * can be; earlier content is assumed to already be well-formed and is left untouched.
     *
     * @param bytes The bytes to trim, assumed to be UTF-8 encoded
     * @return The given bytes, or a copy missing its trailing incomplete character
     */
    public static byte[] trimIncompleteTrailingCharacter(byte[] bytes) {
        int end = bytes.length;
        int scanStart = max(0, end - 4);
        for (int i = end - 1; i >= scanStart; i--) {
            int current = bytes[i] & 0xFF;
            if ((current & 0xC0) != 0x80) {
                // Not a continuation byte: this is the lead byte of the trailing character
                int characterLength = leadByteLength(current);
                return characterLength > 0 && i + characterLength <= end ? bytes : copyOfRange(bytes, 0, i);
            }
        }
        // No lead byte found within the last 4 bytes: leave the (malformed) content untouched
        return bytes;
    }

    /**
     * Gets the number of bytes of the UTF-8 character starting with the given lead byte.
     *
     * @param leadByte The first byte of a UTF-8 character
     * @return The number of bytes of the character, or {@code -1} if it is not a valid lead byte
     */
    private static int leadByteLength(int leadByte) {
        if ((leadByte & 0x80) == 0x00) {
            return 1;
        } else if ((leadByte & 0xE0) == 0xC0) {
            return 2;
        } else if ((leadByte & 0xF0) == 0xE0) {
            return 3;
        } else if ((leadByte & 0xF8) == 0xF0) {
            return 4;
        }
        return -1;
    }

}
