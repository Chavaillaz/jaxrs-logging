package com.chavaillaz.jakarta.rs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveBodyFilterTest {

    private final SensitiveBodyFilter filter = new SensitiveBodyFilter();

    @Test
    @DisplayName("Check a single secret code is masked")
    void checkSingleSecretMasked() {
        // Given
        StringBuilder body = new StringBuilder("{\"secret-code\": \"1234-ABCD\"}");

        // When
        filter.filter(body);

        // Then
        assertEquals("{\"secret-code\": \"masked\"}", body.toString());
    }

    @Test
    @DisplayName("Check every secret code is masked even when replacement length differs from the original")
    void checkMultipleSecretsMasked() {
        // Given
        StringBuilder body = new StringBuilder(
                "{\"a\": {\"secret-code\": \"1\"}, \"b\": {\"secret-code\": \"1234-ABCD-LONG\"}}");

        // When
        filter.filter(body);

        // Then
        String result = body.toString();
        assertEquals("{\"a\": {\"secret-code\": \"masked\"}, \"b\": {\"secret-code\": \"masked\"}}", result);
        assertFalse(result.contains("1234-ABCD-LONG"));
    }

}
