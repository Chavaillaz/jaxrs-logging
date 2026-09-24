package com.chavaillaz.jakarta.rs;

import static java.lang.Math.min;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.commons.io.output.ProxyOutputStream;

/**
 * An output stream wrapping another output stream and limiting the number of bytes
 * effectively written into the wrapped output stream.
 * <p>
 * Note that a limit can cut a multi-byte character in half. Use {@link #isTruncated()} to know whether it
 * did before decoding the captured bytes as text, so such a dangling partial character can be left out
 * rather than decoded to a replacement character (see {@link BoundedLoggedBodyCapture}, which does so for
 * whichever charset the body declares).
 *
 * @deprecated No longer used by this library: {@link BoundedLoggedBodyCapture} keeps what it captures in a
 * buffer applying the limit itself, without a stream in front of it nor a lock on every write.
 */
@Deprecated(since = "4.0", forRemoval = true)
public class BoundedOutputStream extends ProxyOutputStream {

    /**
     * Value of {@link #limit} meaning that no limit is applied.
     */
    public static final int NO_LIMIT = LoggedBodyCapture.NO_LIMIT;

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
     * the captured content is incomplete and may end in the middle of a multi-byte character.
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

}
