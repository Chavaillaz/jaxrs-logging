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
 * Buffer keeping in memory at most a given number of the bytes written to it, those of a body a
 * {@link BoundedBodyCapture} captures.
 * <p>
 * Written to along with the entity stream, it takes no lock, unlike a {@link java.io.ByteArrayOutputStream}: a
 * capture is confined to the thread reading or writing the entity. It hands out the array it fills rather than
 * a copy (see {@link #array()}), and cuts a body wherever it stops growing - at the limit, at the largest array
 * the JVM allocates, or where the heap cannot spare a larger one - reporting it as truncated.
 */
final class CaptureBuffer extends OutputStream {

    /**
     * Largest array the JVM reliably allocates: asking for more fails with an {@link OutOfMemoryError},
     * however much of the heap is free.
     */
    static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    /**
     * Initial capacity of the array, unless the limit is smaller.
     */
    static final int INITIAL_CAPACITY = 1024;

    /**
     * Grows the array to a given length, keeping its content: {@link Arrays#copyOf(byte[], int)}, which a test
     * replaces with an allocation failing as when the heap cannot spare the array.
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
            // Only when bytes were dropped: a body exactly as long as the limit is complete
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
     * Grows the array to hold at least the given number of bytes, doubling it within what this buffer keeps. A
     * heap that cannot spare the larger array leaves the body cut where the current one ends, and the array is
     * not asked to grow again, as the JVM runs a full garbage collection before each allocation it then fails.
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
     * Indicates whether bytes were dropped, what was kept possibly ending in the middle of a character.
     *
     * @return {@code true} if bytes were dropped, {@code false} otherwise
     */
    boolean isTruncated() {
        return truncated;
    }

}
