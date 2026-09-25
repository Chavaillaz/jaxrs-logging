package com.chavaillaz.jakarta.rs.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Logged body filter")
class LoggedBodyFilterTest {

    @Test
    @DisplayName("Check a filter implementing only filter(StringBuilder) is applied to a copy of the body")
    void checkDefaultApplyFiltersCopy() {
        // Given: a filter written against the original contract, as an application's own filter is
        LoggedBodyFilter filter = body -> body.replace(0, 4, "****");
        StringBuilder body = new StringBuilder("1234-ABCD");

        // When
        CharSequence filtered = filter.apply(body);

        // Then: the body given is only read, as a capture may hand the same one to several filters in turn
        assertEquals("****-ABCD", filtered.toString());
        assertEquals("1234-ABCD", body.toString());
    }

}
