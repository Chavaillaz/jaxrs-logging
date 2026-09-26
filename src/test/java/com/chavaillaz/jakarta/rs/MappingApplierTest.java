package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static com.chavaillaz.jakarta.rs.MappingApplier.inApplicationOrder;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;

/**
 * Exercises {@link MappingApplier} on plain maps, without any request context: the mapping behaviour seen
 * through a request is covered by {@link LoggedFilterTest}.
 */
@DisplayName("Mapping applier")
class MappingApplierTest {

    final MappingApplier applier = new MappingApplier(
            (type, name) -> "password".equals(name),
            "query-request-id"::equals);

    // Declares the mappings exercised below, read the way the resolver reads those of a resource method
    interface Resource {

        @LoggedMapping(type = QUERY, mdcKey = "topic", paramNames = {"topic", "subject"})
        void explicit();

        @LoggedMapping(type = QUERY, auto = true, mdcPrefix = "query-")
        void automatic();

        @LoggedMapping(type = HEADER, auto = true, mdcPrefix = "header-")
        @LoggedMapping(type = HEADER, paramNames = "X-Secret")
        void excluded();

        @LoggedMapping(type = HEADER, auto = true)
        @LoggedMapping(type = HEADER, mdcKey = "agent", paramNames = "User-Agent")
        void claimed();

        @LoggedMapping(type = QUERY, mdcKey = "second", paramNames = {"id", "other"})
        @LoggedMapping(type = QUERY, mdcKey = "first", paramNames = "id")
        void competing();

        @LoggedMapping(type = QUERY, auto = true)
        @LoggedMapping(type = QUERY, mdcKey = "b", paramNames = "b")
        @LoggedMapping(type = QUERY, paramNames = "secret")
        @LoggedMapping(type = QUERY, mdcKey = "a", paramNames = "a")
        void unordered();

        @LoggedMapping(type = QUERY, auto = true)
        @LoggedMapping(type = HEADER, mdcKey = "tenant", paramNames = "X-Tenant")
        void overlapping();

    }

    static List<LoggedMapping> mappingsOf(String method) throws Exception {
        return asList(Resource.class.getMethod(method).getAnnotationsByType(LoggedMapping.class));
    }

    /**
     * Applies the mappings of the given method to the given parameters, all of the given type.
     *
     * @return The MDC entries the mappings asked for, in the order they were asked for
     */
    Map<String, String> apply(String method, MappingType type, Map<String, List<String>> parameters) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        applier.apply(
                inApplicationOrder(mappingsOf(method)),
                requested -> requested == type ? parameters : Map.of(),
                entries::put);
        return entries;
    }

    /**
     * Creates the headers of a request, whose names are case-insensitive the way a container's are.
     */
    static Map<String, List<String>> headers(Map<String, List<String>> values) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(values);
        return headers;
    }

    @Test
    @DisplayName("Check an explicit mapping maps the first parameter it names that the request has")
    void checkExplicitMappingTakesFirstDeclaredParameter() throws Exception {
        assertEquals(Map.of("topic", "news"), apply("explicit", QUERY, Map.of("subject", List.of("sport"), "topic", List.of("news"))));
        assertEquals(Map.of("topic", "sport"), apply("explicit", QUERY, Map.of("subject", List.of("sport"))));
        assertEquals(Map.of(), apply("explicit", QUERY, Map.of("unrelated", List.of("value"))));
    }

    @Test
    @DisplayName("Check an automatic mapping maps every parameter under its own name")
    void checkAutomaticMappingMapsEveryParameter() throws Exception {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        parameters.put("topic", List.of("news", "sport"));
        parameters.put("page", List.of("2"));

        assertEquals(Map.of("query-topic", "news", "query-page", "2"), apply("automatic", QUERY, parameters));
    }

    @Test
    @DisplayName("Check an automatic mapping leaves sensitive parameters and taken keys out")
    void checkAutomaticMappingGuards() throws Exception {
        Map<String, List<String>> parameters = Map.of(
                "password", List.of("hunter2"),
                "request-id", List.of("forged"),
                "topic", List.of("news"));

        assertEquals(Map.of("query-topic", "news"), apply("automatic", QUERY, parameters));
    }

    @Test
    @DisplayName("Check a parameter without any value is not mapped")
    void checkParameterWithoutValueSkipped() throws Exception {
        Map<String, List<String>> parameters = new HashMap<>();
        parameters.put("topic", List.of());
        parameters.put("subject", null);

        assertEquals(Map.of(), apply("explicit", QUERY, parameters));
        assertEquals(Map.of(), apply("automatic", QUERY, parameters));
    }

    @Test
    @DisplayName("Check control characters are removed from the keys and values of mapped parameters")
    void checkMappedParametersSanitized() throws Exception {
        assertEquals(Map.of("query-a b", "x y"), apply("automatic", QUERY, Map.of("a\nb", List.of("x\u2028y"))));
        assertEquals(Map.of("topic", "forged  line"), apply("explicit", QUERY, Map.of("topic", List.of("forged\r\nline"))));
    }

    @Test
    @DisplayName("Check an excluded header is not mapped by an automatic mapping, whatever its casing")
    void checkExclusionHonouredWhateverTheCasing() throws Exception {
        Map<String, List<String>> headers = headers(Map.of(
                "x-secret", List.of("secret-value"),
                "Accept", List.of("application/json")));

        assertEquals(Map.of("header-Accept", "application/json"), apply("excluded", HEADER, headers));
    }

    @Test
    @DisplayName("Check a header claimed by an explicit mapping is not mapped again by an automatic one")
    void checkClaimedHeaderNotMappedTwice() throws Exception {
        Map<String, List<String>> headers = headers(Map.of("user-agent", List.of("JUnit")));

        assertEquals(Map.of("agent", "JUnit"), apply("claimed", HEADER, headers));
    }

    @Test
    @DisplayName("Check a mapping naming a parameter another mapping claimed is left out entirely")
    void checkCompetingMappingLeftOut() throws Exception {
        // The second mapping does not fall back to the parameter it alone names: mapping it under its key
        // would read as the value of the parameter it shares with the first one
        Map<String, List<String>> parameters = Map.of("id", List.of("42"), "other", List.of("7"));

        assertEquals(Map.of("first", "42"), apply("competing", QUERY, parameters));
    }

    @Test
    @DisplayName("Check an automatic mapping puts nothing under the key of an explicit one, applied or not")
    void checkAutomaticMappingLeavesExplicitKeysAlone() throws Exception {
        // Given: a tenant the gateway sets in a header, and a client adding a parameter named after its key
        Map<String, List<String>> query = Map.of("tenant", List.of("forged"), "page", List.of("2"));
        Map<String, List<String>> withTenant = headers(Map.of("X-Tenant", List.of("acme")));
        List<LoggedMapping> mappings = inApplicationOrder(mappingsOf("overlapping"));
        Map<String, String> entries = new HashMap<>();
        Map<String, String> entriesWithoutTenant = new HashMap<>();

        // When
        applier.apply(mappings, type -> type == HEADER ? withTenant : query, entries::put);
        applier.apply(mappings, type -> type == HEADER ? Map.of() : query, entriesWithoutTenant::put);

        // Then: without the header, the key is left without any entry rather than given the client's value
        assertEquals(Map.of("tenant", "acme", "page", "2"), entries);
        assertEquals(Map.of("page", "2"), entriesWithoutTenant);
    }

    @Test
    @DisplayName("Check mappings are sorted with exclusions first and automatic mappings last")
    void checkApplicationOrder() throws Exception {
        List<LoggedMapping> mappings = inApplicationOrder(mappingsOf("unordered"));

        assertEquals(List.of("", "a", "b", ""), mappings.stream().map(LoggedMapping::mdcKey).toList());
        assertEquals(List.of(false, false, false, true), mappings.stream().map(LoggedMapping::auto).toList());
    }

    @Test
    @DisplayName("Check a resource without mappings reads nothing from the request")
    void checkNoMappingReadsNothing() {
        Map<String, String> entries = new HashMap<>();

        applier.apply(List.of(), type -> {
            throw new AssertionError("No parameter should be read");
        }, entries::put);

        assertTrue(entries.isEmpty());
    }

}
