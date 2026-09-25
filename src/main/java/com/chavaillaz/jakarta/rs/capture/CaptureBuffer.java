package com.chavaillaz.jakarta.rs.capture;

import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.NO_LIMIT;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.checkLimit;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Objects.checkFromIndexSize;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.BiFunction;

/**
 * Buffer keeping, in memory, at most a given number of the bytes written to it: the bytes of a body a
 * {@link BoundedLoggedBodyCapture} captures.
 * <p>
 * It is written to along with the entity stream, once per chunk read or written - once per byte for a reader
 * reading a byte at a time - so it does as little per write as it can: it applies its limit itself, and takes
 * no lock, as a {@link java.io.ByteArrayOutputStream} does on every write. It is not thread-safe, a capture
 * being confined to the thread reading or writing the entity, and read back by the callback that made it.
 * <p>
 * It hands out the array it fills rather than a copy of it (see {@link #array()}), so the captured body can
 * be decoded straight from where it was written.
 * <p>
 * A body is cut and reported as truncated wherever the buffer stops growing: at the limit, at the largest
 * array the JVM can allocate, or where the heap cannot spare a larger array. In none of these cases does the
 * capture fail the exchange it only observes.
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
     * Grows the array to a given length, keeping its content: {@link Arrays#copyOf(byte[], int)}, which a test
     * replaces with an allocation failing the way it does once the heap cannot spare the array.
     */
    private final BiFunction<byte[], Integer, byte[]> resize;

    /**
     * Most bytes this buffer ever keeps: the limit it was given, or {@link #MAX_CAPACITY} without one - or the
     * length of the array once the heap could not spare a larger one.
     */
    private int capacityLimit;

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
        this(limit, MAX_CAPACITY, Arrays::copyOf);
    }

    /**
     * Creates a buffer keeping at most the given number of bytes, never more than the given capacity, and
     * growing its array with the given function: the parameters that let a body larger than the capacity, or
     * than the heap can hold, be tested without such a body.
     *
     * @param limit       The maximum number of bytes to keep, or {@link LoggedBodyCapture#NO_LIMIT} for no limit
     * @param maxCapacity The maximum number of bytes to keep whatever the limit
     * @param resize      The growth of an array to a given length, keeping its content
     * @throws IllegalArgumentException if the limit is lower than {@link LoggedBodyCapture#NO_LIMIT}
     */
    CaptureBuffer(int limit, int maxCapacity, BiFunction<byte[], Integer, byte[]> resize) {
        checkLimit(limit);
        this.resize = resize;
        this.capacityLimit = limit == NO_LIMIT ? maxCapacity : min(limit, maxCapacity);
        this.bytes = new byte[min(capacityLimit, INITIAL_CAPACITY)];
    }

    @Override
    public void write(int b) {
        if (size < bytes.length || reserve(1) == 1) {
            bytes[size++] = (byte) b;
        } else {
            truncated = true;
        }
    }

    @Override
    public void write(byte[] b, int off, int len) {
        checkFromIndexSize(off, len, b.length);
        int kept = reserve(len);
        if (kept < len) {
            // Reported only when bytes were actually dropped, not when the limit was merely reached: a body
            // exactly as long as the limit is complete, and must not be logged as if part of it were missing
            truncated = true;
        }
        if (kept > 0) {
            System.arraycopy(b, off, bytes, size, kept);
            size += kept;
        }
    }

    /**
     * Makes room in the array for up to the given number of bytes, growing it if this buffer can keep more.
     *
     * @param wanted The number of bytes about to be written
     * @return How many of them the array can take, fewer than wanted once this buffer is full
     */
    private int reserve(int wanted) {
        int kept = min(wanted, capacityLimit - size);
        if (size + kept > bytes.length) {
            grow(size + kept);
        }
        return min(kept, bytes.length - size);
    }

    /**
     * Grows the array to hold at least the given number of bytes, doubling its size so a body written in
     * small chunks is copied a logarithmic number of times, but never beyond what this buffer can keep.
     * <p>
     * A heap that cannot spare the larger array leaves the body kept as far as the current one holds, and
     * reported as truncated as it would be at the limit: only the allocation failed, and the capture must not
     * fail the exchange it observes. The array is not asked to grow again, as the JVM runs a full garbage
     * collection before each allocation it then fails.
     *
     * @param minCapacity The number of bytes the array must be able to hold
     */
    private void grow(int minCapacity) {
        int doubled = (int) min((long) bytes.length * 2, capacityLimit);
        try {
            bytes = resize.apply(bytes, max(doubled, minCapacity));
        } catch (OutOfMemoryError e) {
            capacityLimit = bytes.length;
        }
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
