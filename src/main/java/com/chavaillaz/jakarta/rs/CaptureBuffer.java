package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBodyCapture.NO_LIMIT;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Arrays.copyOf;
import static java.util.Objects.checkFromIndexSize;

import java.io.OutputStream;

/**
 * Buffer keeping, in memory, at most a given number of the bytes written to it: the bytes of a body a
 * {@link BoundedLoggedBodyCapture} captures.
 * <p>
 * It is written to as the branch of the entity stream being read or written, once per chunk the message
 * body reader or writer handles - and once per byte for one reading a byte at a time - so it does as little
 * per write as it can. It takes no lock, where a {@link java.io.ByteArrayOutputStream} takes one on every
 * write, and it is its own limit, where a bounded stream in front of it added a layer to go through. Neither
 * bought anything: an entity stream is read or written by one thread at a time, and what was captured is
 * read back once that is done, by the very callback that captured it, so a capture never has two threads to
 * arbitrate between. This class is therefore not thread-safe, and relies on being confined to the thread
 * reading or writing the entity.
 * <p>
 * It hands out the array it fills rather than a copy of it (see {@link #array()}), so the captured body can
 * be decoded straight from where it was written.
 */
final class CaptureBuffer extends OutputStream {

    /**
     * Largest array the JVM reliably allocates: asking for more fails with an {@link OutOfMemoryError},
     * however much of the heap is free.
     */
    static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    /**
     * Initial capacity of the array, also its final one when a smaller limit is configured, so a small body
     * does not pay for an array sized after a generous limit, and a large one does not pay for the repeated
     * copies of an array growing from a handful of bytes.
     */
    static final int INITIAL_CAPACITY = 1024;

    /**
     * Most bytes this buffer ever keeps: the limit it was given, or {@link #MAX_CAPACITY} without one.
     */
    private final int capacityLimit;

    private byte[] bytes;
    private int size;
    private boolean truncated;

    /**
     * Creates a buffer keeping at most the given number of bytes.
     *
     * @param limit The maximum number of bytes to keep, or {@link LoggedBodyCapture#NO_LIMIT} for no limit
     * @throws IllegalArgumentException if the limit is lower than {@link LoggedBodyCapture#NO_LIMIT}
     */
    CaptureBuffer(int limit) {
        this(limit, MAX_CAPACITY);
    }

    /**
     * Creates a buffer keeping at most the given number of bytes, and never more than the given capacity.
     * <p>
     * A body larger than the capacity is kept up to it and reported as truncated, as any body larger than
     * the limit is, instead of the array failing to grow any further: without a limit, a body of more than
     * two gigabytes otherwise failed the exchange with an {@link OutOfMemoryError} from the buffer growing
     * to capture it. Taking the capacity as a parameter is what lets that be tested without such a body.
     *
     * @param limit       The maximum number of bytes to keep, or {@link LoggedBodyCapture#NO_LIMIT} for no limit
     * @param maxCapacity The maximum number of bytes to keep whatever the limit
     * @throws IllegalArgumentException if the limit is lower than {@link LoggedBodyCapture#NO_LIMIT}
     */
    CaptureBuffer(int limit, int maxCapacity) {
        checkLimit(limit);
        this.capacityLimit = limit == NO_LIMIT ? maxCapacity : min(limit, maxCapacity);
        this.bytes = new byte[min(capacityLimit, INITIAL_CAPACITY)];
    }

    /**
     * Checks the given limit, which is valid when it is {@link LoggedBodyCapture#NO_LIMIT} or a number of
     * bytes.
     *
     * @param limit The limit to check
     * @return The limit, valid
     * @throws IllegalArgumentException if the limit is lower than {@link LoggedBodyCapture#NO_LIMIT}
     */
    static int checkLimit(int limit) {
        if (limit < NO_LIMIT) {
            throw new IllegalArgumentException("Limit must be -1 (unlimited) or a positive value, but was " + limit);
        }
        return limit;
    }

    @Override
    public void write(int b) {
        if (size == capacityLimit) {
            truncated = true;
            return;
        }
        if (size == bytes.length) {
            grow(size + 1);
        }
        bytes[size++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        checkFromIndexSize(off, len, b.length);
        int kept = min(len, capacityLimit - size);
        if (kept < len) {
            // Reported only when bytes were actually dropped, not when the limit was merely reached: a body
            // exactly as long as the limit is complete, and must not be logged as if part of it were missing
            truncated = true;
        }
        if (kept > 0) {
            if (size + kept > bytes.length) {
                grow(size + kept);
            }
            System.arraycopy(b, off, bytes, size, kept);
            size += kept;
        }
    }

    /**
     * Grows the array to hold at least the given number of bytes, doubling its size so a body written in
     * small chunks is copied a logarithmic number of times, but never beyond what this buffer can keep.
     *
     * @param minCapacity The number of bytes the array must be able to hold
     */
    private void grow(int minCapacity) {
        int doubled = (int) min((long) bytes.length * 2, capacityLimit);
        bytes = copyOf(bytes, max(doubled, minCapacity));
    }

    /**
     * Gets the array holding the bytes kept, itself rather than a copy of it: only its first {@link #size()}
     * bytes are part of the body.
     *
     * @return The array holding the bytes kept
     */
    byte[] array() {
        return bytes;
    }

    /**
     * Gets the number of bytes kept.
     *
     * @return The number of bytes kept, at the start of {@link #array()}
     */
    int size() {
        return size;
    }

    /**
     * Indicates whether at least one byte was dropped because the limit was reached, meaning what was kept
     * is incomplete and may end in the middle of a multi-byte character.
     *
     * @return {@code true} if bytes were dropped, {@code false} otherwise
     */
    boolean isTruncated() {
        return truncated;
    }

}
