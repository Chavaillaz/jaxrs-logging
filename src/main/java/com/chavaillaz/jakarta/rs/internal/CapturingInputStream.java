package com.chavaillaz.jakarta.rs.internal;

import static java.lang.Math.min;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Input stream copying each byte of the entity stream it wraps to a capture exactly once, however it is read:
 * bytes read again after {@link #reset()} are not copied twice, and bytes skipped are read, as
 * {@link InputStream#skip(long)} does, so they reach the capture too. It tells when the body ended - read to its
 * end, or closed - for the body of an entity read as a stream to be handed over then.
 */
final class CapturingInputStream extends InputStream {

    private final InputStream in;
    private final OutputStream capture;

    /**
     * What to do once the body ended, whenever it does, and possibly more than once.
     */
    private final Runnable ending;

    /**
     * Number of bytes read so far, moved back by {@link #reset()}.
     */
    private long position;

    /**
     * Number of bytes copied to the capture, the furthest {@link #position} has ever been.
     */
    private long captured;

    /**
     * Position {@link #reset()} moves back to, the start of the stream until {@link #mark(int)} is called,
     * as for a {@link java.io.ByteArrayInputStream}.
     */
    private long markPosition;

    /**
     * Creates a stream reading the given one and copying what it reads to the given capture.
     *
     * @param in      The entity stream to read
     * @param capture The capture to copy the bytes read to
     * @param ending  What to do once the body ended, run each time a read reaches the end of the stream and
     *                when the stream is closed, which it must therefore tolerate
     */
    CapturingInputStream(InputStream in, OutputStream capture, Runnable ending) {
        this.in = in;
        this.capture = capture;
        this.ending = ending;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            if (position == captured) {
                capture.write(b);
                captured++;
            }
            position++;
        } else {
            ending.run();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = in.read(b, off, len);
        if (read > 0) {
            int alreadyCaptured = (int) min(read, captured - position);
            if (alreadyCaptured < read) {
                capture.write(b, off + alreadyCaptured, read - alreadyCaptured);
                captured = position + read;
            }
            position += read;
        } else if (read < 0) {
            ending.run();
        }
        return read;
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    @Override
    public boolean markSupported() {
        return in.markSupported();
    }

    @Override
    public void mark(int readLimit) {
        in.mark(readLimit);
        markPosition = position;
    }

    @Override
    public void reset() throws IOException {
        in.reset();
        position = markPosition;
    }

    @Override
    public void close() throws IOException {
        try {
            in.close();
        } finally {
            ending.run();
        }
    }

}
