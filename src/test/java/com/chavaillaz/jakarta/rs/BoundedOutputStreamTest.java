package com.chavaillaz.jakarta.rs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedOutputStreamTest {

    public static final String DATA = "If debugging is the process of removing software bugs, " +
            "then programming must be the process of putting them in";

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

}