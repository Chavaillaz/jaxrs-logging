package com.chavaillaz.jakarta.rs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Built-in masking body filters")
class MaskingBodyFilterTest {

    static String filter(LoggedBodyFilter filter, String body) {
        StringBuilder builder = new StringBuilder(body);
        filter.filter(builder);
        return builder.toString();
    }

    @Nested
    @DisplayName("JSON")
    class Json {

        private final LoggedBodyFilter filter = new JsonMaskingBodyFilter("password", "token");

        @Test
        @DisplayName("Check a string value is masked and the rest of the document left untouched")
        void checkStringValueMasked() {
            assertEquals("""
                    {"user":"jane","password":"***"}""", filter(filter, """
                    {"user":"jane","password":"hunter2"}"""));
        }

        @Test
        @DisplayName("Check every occurrence is masked, at any depth")
        void checkEveryOccurrenceMasked() {
            assertEquals("""
                    {"a":{"token":"***"},"b":[{"token":"***"}]}""", filter(filter, """
                    {"a":{"token":"abc"},"b":[{"token":"def"}]}"""));
        }

        @Test
        @DisplayName("Check a non-string value is masked too, and the result is still valid JSON")
        void checkScalarValuesMasked() {
            // A value that must not be logged must not leak through by being sent as a number or a
            // boolean rather than as a string
            assertEquals("""
                    {"password":"***","token":"***"}""", filter(filter, """
                    {"password":1234,"token":true}"""));
        }

        @Test
        @DisplayName("Check an escaped quote inside the value does not end the match early")
        void checkEscapedQuoteHandled() {
            assertEquals("""
                    {"password":"***","user":"jane"}""", filter(filter, """
                    {"password":"a\\"b","user":"jane"}"""));
        }

        @Test
        @DisplayName("Check a long value is masked without overflowing the stack")
        void checkLongValueMasked() {
            // A JWT carrying a handful of claims is easily several thousand characters long, which used to
            // be enough for the regular expression to recurse its way through the whole stack
            String token = "a".repeat(100_000);
            assertEquals("""
                    {"token":"***","user":"jane"}""", filter(filter, """
                    {"token":"%s","user":"jane"}""".formatted(token)));
        }

        @Test
        @DisplayName("Check a value cut short by the body limit is masked too")
        void checkTruncatedValueMasked() {
            // The body a filter receives ends wherever the limit fell, possibly in the middle of a secret,
            // which then lacks its closing quote: the part before the cut must not reach the logs either
            assertEquals("{\"user\":\"jane\",\"password\":\"***\"",
                    filter(filter, "{\"user\":\"jane\",\"password\":\"hun"));
        }

        @Test
        @DisplayName("Check a value cut short in the middle of an escape sequence is masked too")
        void checkValueTruncatedWithinEscapeMasked() {
            assertEquals("{\"password\":\"***\"", filter(filter, "{\"password\":\"hun\\"));
        }

        @Test
        @DisplayName("Check whitespace around the separator is tolerated")
        void checkWhitespaceTolerated() {
            assertEquals("""
                    { "password" : "***" }""", filter(filter, """
                    { "password" : "hunter2" }"""));
        }

        @Test
        @DisplayName("Check a value containing regex replacement syntax is not interpreted")
        void checkReplacementSyntaxNotInterpreted() {
            // $1 and \\ carry meaning in a replacement string, and a payload is free to contain them
            assertEquals("""
                    {"password":"***"}""", filter(filter, """
                    {"password":"$1\\\\x"}"""));
        }

        @Test
        @DisplayName("Check a property whose name only contains a masked one is left alone")
        void checkPartialNameNotMasked() {
            assertEquals("""
                    {"password_hint":"pet name"}""", filter(filter, """
                    {"password_hint":"pet name"}"""));
        }

        @Test
        @DisplayName("Check a body without any masked property is returned untouched")
        void checkUnaffectedBodyUntouched() {
            String body = """
                    {"user":"jane"}""";
            assertEquals(body, filter(filter, body));
        }

        @Test
        @DisplayName("Check declaring no property to mask is rejected")
        void checkNoPropertyRejected() {
            assertThrows(IllegalArgumentException.class, () -> new JsonMaskingBodyFilter(List.of().toArray(String[]::new)));
        }

    }

    @Nested
    @DisplayName("Form")
    class Form {

        private final LoggedBodyFilter filter = new FormMaskingBodyFilter("password", "client_secret");

        @Test
        @DisplayName("Check a parameter value is masked and its neighbours left untouched")
        void checkValueMasked() {
            assertEquals("username=jane&password=***&scope=read",
                    filter(filter, "username=jane&password=hunter2&scope=read"));
        }

        @Test
        @DisplayName("Check the first parameter of the body is masked too")
        void checkFirstParameterMasked() {
            assertEquals("password=***&username=jane",
                    filter(filter, "password=hunter2&username=jane"));
        }

        @Test
        @DisplayName("Check a value equal to a masked parameter name is not mistaken for one")
        void checkValueEqualToNameNotMasked() {
            // An OAuth token request carries both, so this is not a hypothetical case
            assertEquals("grant_type=password&password=***",
                    filter(filter, "grant_type=password&password=hunter2"));
        }

        @Test
        @DisplayName("Check an empty value is still masked")
        void checkEmptyValueMasked() {
            assertEquals("password=***&username=jane",
                    filter(filter, "password=&username=jane"));
        }

        @Test
        @DisplayName("Check declaring no parameter to mask is rejected")
        void checkNoParameterRejected() {
            assertThrows(IllegalArgumentException.class, () -> new FormMaskingBodyFilter(List.of().toArray(String[]::new)));
        }

    }

    @Nested
    @DisplayName("Regex")
    class Regex {

        @Test
        @DisplayName("Check only the captured group is replaced")
        void checkOnlyGroupReplaced() {
            LoggedBodyFilter filter = new RegexMaskingBodyFilter("(?i)Bearer (\\S+)", 1);
            assertEquals("Authorization: Bearer *** and more",
                    filter(filter, "Authorization: Bearer abc.def.ghi and more"));
        }

        @Test
        @DisplayName("Check every match is replaced")
        void checkEveryMatchReplaced() {
            LoggedBodyFilter filter = new RegexMaskingBodyFilter("id=(\\d+)", 1);
            assertEquals("id=***&id=***", filter(filter, "id=12&id=34"));
        }

    }

    @Nested
    @DisplayName("Composition")
    class Composition {

        @Test
        @DisplayName("Check filters declared in order run in that order on the same body")
        void checkFiltersComposed() {
            StringBuilder body = new StringBuilder("""
                    {"password":"hunter2","token":"abc"}""");
            new JsonMaskingBodyFilter("password").filter(body);
            new JsonMaskingBodyFilter("token").filter(body);
            assertEquals("""
                    {"password":"***","token":"***"}""", body.toString());
        }

        @Test
        @DisplayName("Check a filter leaving a body untouched does not replace its content")
        void checkUntouchedBodyKeepsItsBuilder() {
            // The filter must be able to run on every request without allocating a copy of a payload it
            // has nothing to mask in
            StringBuilder body = new StringBuilder("""
                    {"user":"jane"}""");
            StringBuilder same = body;
            new JsonMaskingBodyFilter("password").filter(body);
            assertSame(same, body);
            assertEquals("""
                    {"user":"jane"}""", body.toString());
        }

    }

}
