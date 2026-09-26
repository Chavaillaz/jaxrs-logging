package com.chavaillaz.jakarta.rs.internal;

import static java.lang.Math.min;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Input stream copying each byte of the entity stream it wraps to a capture, exactly once, however the
 * message body reader goes through it.
 * <p>
 * Copying whatever each read returns is not enough for that. A reader peeking at a stream that supports it
 * reads bytes again after {@link #reset()}, which would copy them twice, so this stream keeps track of how
 * far reading got and how much of it the capture already holds. A reader skipping bytes would have the
 * wrapped stream skip them unseen, so this stream skips by reading, as {@link InputStream#skip(long)} does,
 * and the bytes skipped reach the capture too.
 * <p>
 * It tells when the body it copies has ended - it was read to its end, or closed - for the capture of an
 * entity read as a stream, which the application reads once the providers are done with it, to be handed
 * over then.
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
