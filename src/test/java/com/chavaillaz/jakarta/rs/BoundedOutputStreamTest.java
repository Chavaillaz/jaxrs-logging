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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedOutputStreamTest {

    public static final String DATA = "If debugging is the process of removing software bugs, " +
            "then programming must be the process of putting them in";

    @ParameterizedTest
    @ValueSource(ints = {-2, -10, Integer.MIN_VALUE})
    void invalidLimit_rejected(int limit) {
        // given
        var wrapped = new ByteArrayOutputStream();

        // when / then
        assertThrows(IllegalArgumentException.class, () -> new BoundedOutputStream(wrapped, limit));
    }

    @Test
    void moreThanLimit_full() throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, 10);

        // when
        bounded.write(DATA.getBytes(UTF_8));

        // then
        assertEquals("If debuggi", wrapped.toString(UTF_8));
    }

    @Test
    void moreThanLimit_fullSequential() throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, 10);

        // when
        for (byte b : DATA.getBytes(UTF_8)) {
            bounded.write(b);
        }

        // then
        assertEquals("If debuggi", wrapped.toString(UTF_8));
    }

    @Test
    void moreThanLimit_fullMultiple() throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, 10);

        // when
        bounded.write(DATA.getBytes(UTF_8), 0, 5);
        bounded.write(DATA.getBytes(UTF_8), 5, 10);
        bounded.write(DATA.getBytes(UTF_8), 15, 100);

        // then
        assertEquals("If debuggi", wrapped.toString(UTF_8));
    }

    @Test
    void moreThanLimit_partial() throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, 10);

        // when
        bounded.write(DATA.getBytes(UTF_8), 5, 15);

        // then
        assertEquals("bugging is", wrapped.toString(UTF_8));
    }

    @Test
    void moreThanLimit_partialMultiple() throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, 10);

        // when
        bounded.write(DATA.getBytes(UTF_8), 5, 10);
        bounded.write(DATA.getBytes(UTF_8), 15, 10);

        // then
        assertEquals("bugging is", wrapped.toString(UTF_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 150})
    void lessThanLimit_full(int limit) throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, limit);

        // when
        bounded.write(DATA.getBytes(UTF_8));

        // then
        assertEquals(DATA, wrapped.toString(UTF_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 150})
    void lessThanLimit_fullSequential(int limit) throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, limit);

        // when
        for (byte b : DATA.getBytes(UTF_8)) {
            bounded.write(b);
        }

        // then
        assertEquals(DATA, wrapped.toString(UTF_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 150})
    void lessThanLimit_fullMultiple(int limit) throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, limit);

        // when
        bounded.write(DATA.getBytes(UTF_8), 0, 10);
        bounded.write(DATA.getBytes(UTF_8), 10, 10);
        bounded.write(DATA.getBytes(UTF_8), 20, 90);

        // then
        assertEquals(DATA, wrapped.toString(UTF_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 150})
    void lessThanLimit_partial(int limit) throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, limit);

        // when
        bounded.write(DATA.getBytes(UTF_8), 5, 20);

        // then
        assertEquals("bugging is the proce", wrapped.toString(UTF_8));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 150})
    void lessThanLimit_partialMultiple(int limit) throws IOException {
        // given
        var wrapped = new ByteArrayOutputStream();
        var bounded = new BoundedOutputStream(wrapped, limit);

        // when
        bounded.write(DATA.getBytes(UTF_8), 10, 10);
        bounded.write(DATA.getBytes(UTF_8), 20, 10);

        // then
        assertEquals("ng is the process of", wrapped.toString(UTF_8));
    }

    @Test
    @DisplayName("Check isTruncated is false when no limit is applied")
    void isTruncated_unlimited() throws IOException {
        var bounded = new BoundedOutputStream(new ByteArrayOutputStream(), -1);
        bounded.write(DATA.getBytes(UTF_8));
        assertFalse(bounded.isTruncated());
    }

    @Test
    @DisplayName("Check isTruncated is false when written bytes stay under the limit")
    void isTruncated_underLimit() throws IOException {
        var bounded = new BoundedOutputStream(new ByteArrayOutputStream(), 1000);
        bounded.write(DATA.getBytes(UTF_8));
        assertFalse(bounded.isTruncated());
    }

    @Test
    @DisplayName("Check isTruncated is true once the limit is reached")
    void isTruncated_atLimit() throws IOException {
        var bounded = new BoundedOutputStream(new ByteArrayOutputStream(), 10);
        bounded.write(DATA.getBytes(UTF_8));
        assertTrue(bounded.isTruncated());
    }

    @Test
    @DisplayName("Check trimming leaves complete ASCII content untouched")
    void trimIncompleteTrailingCharacter_completeAscii() {
        byte[] bytes = "If debuggi".getBytes(UTF_8);
        assertArrayEquals(bytes, trimIncompleteTrailingCharacter(bytes));
    }

    @Test
    @DisplayName("Check trimming leaves an empty array untouched")
    void trimIncompleteTrailingCharacter_empty() {
        assertArrayEquals(new byte[0], trimIncompleteTrailingCharacter(new byte[0]));
    }

    @Test
    @DisplayName("Check trimming leaves a complete multi-byte character untouched")
    void trimIncompleteTrailingCharacter_completeMultiByte() {
        // "Café" ends with a complete 2-byte character (é = 0xC3 0xA9)
        byte[] bytes = "Café".getBytes(UTF_8);
        assertArrayEquals(bytes, trimIncompleteTrailingCharacter(bytes));
    }

    @Test
    @DisplayName("Check trimming removes a 2-byte character cut after its lead byte")
    void trimIncompleteTrailingCharacter_danglingTwoByteLeadByte() {
        // "Café" (5 bytes: C,a,f,0xC3,0xA9) truncated right after the lead byte of é
        byte[] bytes = copyOfRange("Café".getBytes(UTF_8), 0, 4);
        assertArrayEquals("Caf".getBytes(UTF_8), trimIncompleteTrailingCharacter(bytes));
    }

    @Test
    @DisplayName("Check trimming removes a 3-byte character cut after one or two of its bytes")
    void trimIncompleteTrailingCharacter_danglingThreeByteCharacter() {
        // "a€" (€ = 0xE2 0x82 0xAC) truncated after 1 then 2 of its 3 bytes
        byte[] full = "a€".getBytes(UTF_8);
        assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 2)));
        assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 3)));
    }

    @Test
    @DisplayName("Check trimming removes a 4-byte character cut after one, two or three of its bytes")
    void trimIncompleteTrailingCharacter_danglingFourByteCharacter() {
        // "a😀" (grinning face emoji = 0xF0 0x9F 0x98 0x80) truncated after each partial length
        byte[] full = "a😀".getBytes(UTF_8);
        assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 2)));
        assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 3)));
        assertArrayEquals("a".getBytes(UTF_8), trimIncompleteTrailingCharacter(copyOfRange(full, 0, 4)));
    }

}