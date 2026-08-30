package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.BoundedOutputStream.trimIncompleteTrailingCharacter;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.copyOfRange;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        @DisplayName("Check isTruncated is true once the limit is reached")
        void checkTruncatedAtLimit() throws IOException {
            var bounded = new BoundedOutputStream(new ByteArrayOutputStream(), 10);
            bounded.write(DATA.getBytes(UTF_8));
            assertTrue(bounded.isTruncated());
        }

    }

    @Nested
    @DisplayName("Trimming a dangling trailing character")
    class TrailingCharacterTrimming {

        @Test
        @DisplayName("Check trimming leaves complete ASCII content untouched")
        void checkCompleteAsciiUntouched() {
            byte[] bytes = "If debuggi".getBytes(UTF_8);
            assertArrayEquals(bytes, trimIncompleteTrailingCharacter(bytes));
        }

        @Test
        @DisplayName("Check trimming leaves an empty array untouched")
        void checkEmptyArrayUntouched() {
            assertArrayEquals(new byte[0], trimIncompleteTrailingCharacter(new byte[0]));
        }

        @Test
        @DisplayName("Check trimming leaves a complete multi-byte character untouched")
        void checkCompleteMultiByteCharacterUntouched() {
            // "Café" ends with a complete 2-byte character (é = 0xC3 0xA9)
            byte[] bytes = "Café".getBytes(UTF_8);
            assertArrayEquals(bytes, trimIncompleteTrailingCharacter(bytes));
        }

        @Test
        @DisplayName("Check trimming removes a 2-byte character cut after its lead byte")
        void checkDanglingTwoByteLeadByteRemoved() {
            // "Café" (5 bytes: C,a,f,0xC3,0xA9) truncated right after the lead byte of é
            byte[] bytes = copyOfRange("Café".getBytes(UTF_8), 0, 4);
            assertArrayEquals("Caf".getBytes(UTF_8), trimIncompleteTrailingCharacter(bytes));
        }

        @Test
        @DisplayName("Check trimming removes a 3-byte character cut after one or two of its bytes")
        void checkDanglingThreeByteCharacterRemoved() {
            // "a€" (€ = 0xE2 0x82 0xAC) truncated after 1 then 2 of its 3 bytes
            byte[] full = "a€".getBytes(UTF_8);
            assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 2)));
            assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 3)));
        }

        @Test
        @DisplayName("Check trimming removes a 4-byte character cut after one, two or three of its bytes")
        void checkDanglingFourByteCharacterRemoved() {
            // "a😀" (grinning face emoji = 0xF0 0x9F 0x98 0x80) truncated after each partial length
            byte[] full = "a😀".getBytes(UTF_8);
            assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 2)));
            assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 3)));
            assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 4)));
        }

    }

}
