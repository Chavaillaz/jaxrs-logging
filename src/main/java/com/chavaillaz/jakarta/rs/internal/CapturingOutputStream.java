package com.chavaillaz.jakarta.rs.internal;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Output stream writing to the entity stream it wraps, and copying to a capture each byte the entity stream took:
 * the counterpart of {@link CapturingInputStream} for an entity written. A byte the entity stream failed to write
 * was never sent, so it is not captured either.
 * <p>
 * Confined to the thread writing the entity, it takes no lock, and it relies on the capture not to throw, as the
 * sink of a {@link GuardedBodyCapture} does not.
 */
final class CapturingOutputStream extends OutputStream {

    private final OutputStream out;
    private final OutputStream capture;

    /**
     * Creates a stream writing to the given one and copying what it writes to the given capture.
     *
     * @param out     The entity stream to write to
     * @param capture The capture to copy the bytes written to
     */
    CapturingOutputStream(OutputStream out, OutputStream capture) {
        this.out = out;
        this.capture = capture;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        capture.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        capture.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        out.flush();
        capture.flush();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Closes the capture even when closing the entity stream fails.
     */
    @Override
    public void close() throws IOException {
        try {
            out.close();
        } finally {
            capture.close();
        }
    }

}
