package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBodyCapture.NO_LIMIT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Capture buffer")
class CaptureBufferTest {

    static final byte[] DATA = ("If debugging is the process of removing software bugs, "
            + "then programming must be the process of putting them in").getBytes(UTF_8);

    static String content(CaptureBuffer buffer) {
        return new String(buffer.array(), 0, buffer.size(), UTF_8);
    }

    @ParameterizedTest(name = "limit={0}")
    @ValueSource(ints = {-2, -10, Integer.MIN_VALUE})
    @DisplayName("Check a limit lower than -1 is rejected, naming what has to be fixed")
    void checkInvalidLimitRejected(int limit) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> new CaptureBuffer(limit));
        assertTrue(thrown.getMessage().contains("Limit must be -1 (unlimited) or a positive value"));
    }

    @Test
    @DisplayName("Check a single write is cut at the limit, and reported as truncated")
    void checkWriteCutAtLimit() {
        CaptureBuffer buffer = new CaptureBuffer(10);
        buffer.write(DATA, 0, DATA.length);
        assertEquals("If debuggi", content(buffer));
        assertTrue(buffer.isTruncated());
    }

    @Test
    @DisplayName("Check byte-by-byte writes stop at the limit")
    void checkByteWritesStopAtLimit() {
        CaptureBuffer buffer = new CaptureBuffer(10);
        for (byte b : DATA) {
            buffer.write(b);
        }
        assertEquals("If debuggi", content(buffer));
        assertTrue(buffer.isTruncated());
    }

    @Test
    @DisplayName("Check several writes together stop at the limit")
    void checkSeveralWritesStopAtLimit() {
        CaptureBuffer buffer = new CaptureBuffer(10);
        buffer.write(DATA, 0, 5);
        buffer.write(DATA, 5, 10);
        buffer.write(DATA, 15, 50);
        assertEquals("If debuggi", content(buffer));
        assertTrue(buffer.isTruncated());
    }

    @Test
    @DisplayName("Check a body exactly as long as the limit is not reported as truncated")
    void checkBodyAtExactlyTheLimitNotTruncated() {
        // Nothing was dropped: reporting truncation here would make the logs claim content is missing from
        // a body that was captured whole
        CaptureBuffer buffer = new CaptureBuffer(DATA.length);
        buffer.write(DATA, 0, DATA.length);
        assertFalse(buffer.isTruncated());

        buffer.write('!');
        assertTrue(buffer.isTruncated());
        assertEquals(DATA.length, buffer.size());
    }

    @ParameterizedTest(name = "limit={0}")
    @ValueSource(ints = {NO_LIMIT, 150})
    @DisplayName("Check a body under the limit, or without one, is kept whole")
    void checkBodyUnderLimitKeptWhole(int limit) {
        CaptureBuffer written = new CaptureBuffer(limit);
        written.write(DATA, 0, DATA.length);
        CaptureBuffer writtenByteByByte = new CaptureBuffer(limit);
        for (byte b : DATA) {
            writtenByteByByte.write(b);
        }

        assertArrayEquals(DATA, Arrays.copyOf(written.array(), written.size()));
        assertArrayEquals(DATA, Arrays.copyOf(writtenByteByByte.array(), writtenByteByByte.size()));
        assertFalse(written.isTruncated());
        assertFalse(writtenByteByByte.isTruncated());
    }

    @Test
    @DisplayName("Check a body larger than the initial capacity is kept whole, the array growing to fit it")
    void checkLargeBodyKeptWhole() {
        byte[] large = new byte[10 * CaptureBuffer.INITIAL_CAPACITY + 7];
        new Random(42).nextBytes(large);
        CaptureBuffer buffer = new CaptureBuffer(NO_LIMIT);

        for (int off = 0; off < large.length; off += 100) {
            buffer.write(large, off, Math.min(100, large.length - off));
        }

        assertArrayEquals(large, Arrays.copyOf(buffer.array(), buffer.size()));
        assertFalse(buffer.isTruncated());
    }

    @Test
    @DisplayName("Check a limit of zero keeps nothing, and reports what it dropped")
    void checkZeroLimitKeepsNothing() {
        CaptureBuffer buffer = new CaptureBuffer(0);
        buffer.write(DATA, 0, DATA.length);
        buffer.write('!');
        assertEquals(0, buffer.size());
        assertTrue(buffer.isTruncated());
    }

    @Test
    @DisplayName("Check a body larger than an array can hold is cut and reported as truncated rather than failing")
    void checkBodyBeyondCapacityCut() {
        // Without a limit, a body of more than two gigabytes would otherwise fail the exchange with an
        // OutOfMemoryError from the buffer growing past what an array can hold
        CaptureBuffer buffer = new CaptureBuffer(NO_LIMIT, 16, Arrays::copyOf);
        buffer.write(DATA, 0, DATA.length);
        assertEquals("If debugging is ", content(buffer));
        assertTrue(buffer.isTruncated());
    }

    @Test
    @DisplayName("Check a body the heap cannot spare a larger array for is cut there and reported as truncated")
    void checkBodyBeyondHeapCut() {
        // Given: arrays of more than 4 KiB failing the way they do once the heap is short of memory
        AtomicInteger resizes = new AtomicInteger();
        CaptureBuffer buffer = new CaptureBuffer(NO_LIMIT, CaptureBuffer.MAX_CAPACITY, (bytes, length) -> {
            resizes.incrementAndGet();
            if (length > 4096) {
                throw new OutOfMemoryError("Java heap space");
            }
            return Arrays.copyOf(bytes, length);
        });
        byte[] large = new byte[10_000];
        new Random(42).nextBytes(large);

        // When: an error escaping would be rethrown by JUnit as unrecoverable, crashing the whole test run
        try {
            for (int off = 0; off < large.length; off += 100) {
                buffer.write(large, off, 100);
            }
            buffer.write('!');
        } catch (OutOfMemoryError e) {
            fail("The capture failed with the allocation of its array", e);
        }

        // Then: the body is kept as far as the array holds, which is not asked to grow again after failing to
        assertArrayEquals(Arrays.copyOf(large, 4096), Arrays.copyOf(buffer.array(), buffer.size()));
        assertTrue(buffer.isTruncated());
        assertEquals(3, resizes.get());
    }

    @Test
    @DisplayName("Check a write outside of the array given is rejected, as by any output stream")
    void checkInvalidRangeRejected() {
        CaptureBuffer buffer = new CaptureBuffer(NO_LIMIT);
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.write(DATA, DATA.length - 1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.write(DATA, -1, 1));
        assertEquals(0, buffer.size());
    }

}
