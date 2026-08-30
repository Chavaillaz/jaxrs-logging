package com.chavaillaz.jakarta.rs;

import static java.lang.Math.min;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.commons.io.output.ProxyOutputStream;

/**
 * An output stream wrapping another output stream and limiting the number of bytes
 * effectively written into the wrapped output stream.
 * <p>
 * Note that a limit can cut a multi-byte UTF-8 character in half when the wrapped bytes are later
 * decoded as text, which may leave a replacement character at the end of the truncated content.
 */
public class BoundedOutputStream extends ProxyOutputStream {

    private final int limit;
    private int writtenBytes = 0;

    /**
     * Creates a new bounded output stream.
     *
     * @param out   The output stream to wrap
     * @param limit The maximum number of bytes to write into the wrapped output stream,
     *              or {@code -1} to write without any limit
     * @throws IllegalArgumentException if the limit is lower than {@code -1}
     */
    public BoundedOutputStream(OutputStream out, int limit) {
        super(out);
        if (limit < -1) {
            throw new IllegalArgumentException("Limit must be -1 (unlimited) or a positive value, but was " + limit);
        }
        this.limit = limit;
    }

    @Override
    public void write(int b) throws IOException {
        if (writtenBytes < limit || limit == -1) {
            super.write(b);
            writtenBytes++;
        }
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (limit == -1) {
            super.write(b, off, len);
            writtenBytes += len;
        } else {
            int count = min(len, limit - writtenBytes);
            if (count > 0) {
                super.write(b, off, count);
                writtenBytes += count;
            }
        }
    }

}