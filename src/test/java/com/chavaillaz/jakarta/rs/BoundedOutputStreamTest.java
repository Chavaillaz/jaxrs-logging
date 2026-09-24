package com.chavaillaz.jakarta.rs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Bounded output stream")
class BoundedOutputStreamTest {

    public static final String DATA = "If debugging is the process of removing software bugs, " +
            "then programming must be the process of putting them in";

    @ParameterizedTest(name = "limit={0}")
    @ValueSource(ints = {-2, -10, Integer.MIN_VALUE})
    @DisplayName("Check a limit lower than -1 is rejected")
    void checkInvalidLimitRejected(int limit) {
        // Given
        var wrapped = new ByteArrayOutputStream();

        // When / Then
        assertThrows(IllegalArgumentException.class, () -> new BoundedOutputStream(wrapped, limit));
    }

    @Nested
    @DisplayName("Writing more than the limit")
    class WritingMoreThanLimit {

        @Test
        @DisplayName("Check a single write(byte[]) call is truncated to the limit")
        void checkFullWriteIsTruncated() throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, 10);

            // When
            bounded.write(DATA.getBytes(UTF_8));

            // Then
            assertEquals("If debuggi", wrapped.toString(UTF_8));
        }

        @Test
        @DisplayName("Check byte-by-byte writes stop once the limit is reached")
        void checkSequentialWritesAreTruncated() throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, 10);

            // When
            for (byte b : DATA.getBytes(UTF_8)) {
                bounded.write(b);
            }

            // Then
            assertEquals("If debuggi", wrapped.toString(UTF_8));
        }

        @Test
        @DisplayName("Check several write(byte[], off, len) calls together stop once the limit is reached")
        void checkMultipleWritesAreTruncated() throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, 10);

            // When
            bounded.write(DATA.getBytes(UTF_8), 0, 5);
            bounded.write(DATA.getBytes(UTF_8), 5, 10);
            bounded.write(DATA.getBytes(UTF_8), 15, 100);

            // Then
            assertEquals("If debuggi", wrapped.toString(UTF_8));
        }

        @Test
        @DisplayName("Check a single partial write(byte[], off, len) call is truncated to the limit")
        void checkPartialWriteIsTruncated() throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, 10);

            // When
            bounded.write(DATA.getBytes(UTF_8), 5, 15);

            // Then
            assertEquals("bugging is", wrapped.toString(UTF_8));
        }

        @Test
        @DisplayName("Check several partial write(byte[], off, len) calls together stop once the limit is reached")
        void checkMultiplePartialWritesAreTruncated() throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, 10);

            // When
            bounded.write(DATA.getBytes(UTF_8), 5, 10);
            bounded.write(DATA.getBytes(UTF_8), 15, 10);

            // Then
            assertEquals("bugging is", wrapped.toString(UTF_8));
        }

    }

    @Nested
    @DisplayName("Writing less than the limit")
    class WritingLessThanLimit {

        @ParameterizedTest(name = "limit={0}")
        @ValueSource(ints = {-1, 150})
        @DisplayName("Check a single write(byte[]) call is written in full, unlimited or under the limit")
        void checkFullWriteIsNotTruncated(int limit) throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, limit);

            // When
            bounded.write(DATA.getBytes(UTF_8));

            // Then
            assertEquals(DATA, wrapped.toString(UTF_8));
        }

        @ParameterizedTest(name = "limit={0}")
        @ValueSource(ints = {-1, 150})
        @DisplayName("Check byte-by-byte writes are all written, unlimited or under the limit")
        void checkSequentialWritesAreNotTruncated(int limit) throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, limit);

            // When
            for (byte b : DATA.getBytes(UTF_8)) {
                bounded.write(b);
            }

            // Then
            assertEquals(DATA, wrapped.toString(UTF_8));
        }

        @ParameterizedTest(name = "limit={0}")
        @ValueSource(ints = {-1, 150})
        @DisplayName("Check several write(byte[], off, len) calls are all written, unlimited or under the limit")
        void checkMultipleWritesAreNotTruncated(int limit) throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, limit);

            // When
            bounded.write(DATA.getBytes(UTF_8), 0, 10);
            bounded.write(DATA.getBytes(UTF_8), 10, 10);
            bounded.write(DATA.getBytes(UTF_8), 20, 90);

            // Then
            assertEquals(DATA, wrapped.toString(UTF_8));
        }

        @ParameterizedTest(name = "limit={0}")
        @ValueSource(ints = {-1, 150})
        @DisplayName("Check a single partial write(byte[], off, len) call is written in full, unlimited or under the limit")
        void checkPartialWriteIsNotTruncated(int limit) throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, limit);

            // When
            bounded.write(DATA.getBytes(UTF_8), 5, 20);

            // Then
            assertEquals("bugging is the proce", wrapped.toString(UTF_8));
        }

        @ParameterizedTest(name = "limit={0}")
        @ValueSource(ints = {-1, 150})
        @DisplayName("Check several partial write(byte[], off, len) calls are all written, unlimited or under the limit")
        void checkMultiplePartialWritesAreNotTruncated(int limit) throws IOException {
            // Given
            var wrapped = new ByteArrayOutputStream();
            var bounded = new BoundedOutputStream(wrapped, limit);

            // When
            bounded.write(DATA.getBytes(UTF_8), 10, 10);
            bounded.write(DATA.getBytes(UTF_8), 20, 10);

            // Then
            assertEquals("ng is the process of", wrapped.toString(UTF_8));
        }

    }

    @Nested
    @DisplayName("Truncation detection")
    class TruncationDetection {

        @Test
        @DisplayName("Check isTruncated is false when no limit is applied")
        void checkNotTruncatedWhenUnlimited() throws IOException {
            var bounded = new BoundedOutputStream(new ByteArrayOutputStream(), -1);
            bounded.write(DATA.getBytes(UTF_8));
            assertFalse(bounded.isTruncated());
        }

        @Test
        @DisplayName("Check isTruncated is false when written bytes stay under the limit")
        void checkNotTruncatedUnderLimit() throws IOException {
            var bounded = new BoundedOutputStream(new ByteArrayOutputStream(), 1000);
            bounded.write(DATA.getBytes(UTF_8));
            assertFalse(bounded.isTruncated());
        }

        @Test
        @DisplayName("Check isTruncated is true once bytes have been dropped")
        void checkTruncatedAtLimit() throws IOException {
            var bounded = new BoundedOutputStream(new ByteArrayOutputStream(), 10);
            bounded.write(DATA.getBytes(UTF_8));
            assertTrue(bounded.isTruncated());
        }

        @Test
        @DisplayName("Check isTruncated is false when the content is exactly as long as the limit")
        void checkNotTruncatedAtExactlyTheLimit() throws IOException {
            byte[] data = DATA.getBytes(UTF_8);
            var bounded = new BoundedOutputStream(new ByteArrayOutputStream(), data.length);
            bounded.write(data);
            // Nothing was dropped: reporting truncation here would make the logs claim content is
            // missing from a body that was captured whole
            assertFalse(bounded.isTruncated());
        }

        @Test
        @DisplayName("Check isTruncated is true when a single byte is dropped past the limit")
        void checkTruncatedOnSingleByteWrite() throws IOException {
            var bounded = new BoundedOutputStream(new ByteArrayOutputStream(), 1);
            bounded.write('a');
            assertFalse(bounded.isTruncated());
            bounded.write('b');
            assertTrue(bounded.isTruncated());
        }

    }

}
