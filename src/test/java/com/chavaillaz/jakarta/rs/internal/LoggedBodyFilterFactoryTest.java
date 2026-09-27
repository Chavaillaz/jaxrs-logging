package com.chavaillaz.jakarta.rs.internal;

import static com.chavaillaz.jakarta.rs.capture.BoundedBodyCapture.FILTERING_FAILURE_MARKER;
import static com.chavaillaz.jakarta.rs.internal.LoggedBodyFilterFactory.FAILED_BODY_FILTER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.chavaillaz.jakarta.rs.SensitiveBodyFilter;
import com.chavaillaz.jakarta.rs.capture.BoundedBodyCapture;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;

@DisplayName("Logged body filter factory")
class LoggedBodyFilterFactoryTest {

    private final LoggedBodyFilterFactory factory = new LoggedBodyFilterFactory();

    @Test
    @DisplayName("Check the same filter class only gets instantiated once and reused across calls")
    void checkInstancesAreCachedByClass() {
        // When
        LoggedBodyFilter first = factory.getInstance(SensitiveBodyFilter.class);
        LoggedBodyFilter second = factory.getInstance(SensitiveBodyFilter.class);

        // Then
        assertSame(first, second);
        assertEquals(1, factory.cache.size());
    }

    @Test
    @DisplayName("Check getInstances deduplicates a filter class referenced more than once")
    void checkInstancesDeduplicateRepeatedClasses() {
        // Given
        @SuppressWarnings("unchecked")
        Class<? extends LoggedBodyFilter>[] filterTypes = new Class[]{SensitiveBodyFilter.class, SensitiveBodyFilter.class};

        // When
        List<LoggedBodyFilter> instances = factory.getInstances(filterTypes);

        // Then: same class referenced twice still yields a single instance
        assertEquals(1, instances.size());
    }

    @Test
    @DisplayName("Check getInstances preserves the declaration order of filter classes")
    void checkInstancesPreserveDeclarationOrder() {
        // When: resolved repeatedly, ruling out an order that only happens to match declaration order by
        // chance (e.g. a HashSet whose bucket layout is coincidentally insertion-ordered for these two
        // particular classes)
        for (int i = 0; i < 20; i++) {
            List<LoggedBodyFilter> instances = factory.getInstances(List.of(AppendA.class, AppendB.class));
            StringBuilder body = new StringBuilder();
            instances.forEach(instance -> instance.filter(body));

            // Then
            assertEquals("AB", body.toString());
        }
    }

    public static class AppendA implements LoggedBodyFilter {

        @Override
        public void filter(StringBuilder body) {
            body.append("A");
        }

    }

    public static class AppendB implements LoggedBodyFilter {

        @Override
        public void filter(StringBuilder body) {
            body.append("B");
        }

    }

    @Test
    @DisplayName("Check a body filter failing to instantiate is cached as failed instead of being retried every call")
    void checkFailedInstantiationIsCachedNotRetried() {
        // Given: a filter class with no no-arg constructor, so instantiation always throws
        Class<UninstantiableBodyFilter> type = UninstantiableBodyFilter.class;

        // When
        LoggedBodyFilter first = factory.getInstance(type);
        LoggedBodyFilter second = factory.getInstance(type);

        // Then: the same sentinel is returned both times, from a single cache entry,
        // instead of reflection (and the accompanying error log) being retried on every call
        assertSame(first, second);
        assertEquals(1, factory.cache.size());
    }

    @Test
    @DisplayName("Check a body filter failing to instantiate drops the body rather than letting it through unfiltered")
    void checkFailedInstantiationDropsTheBody() throws IOException {
        // A filter is a "this must never reach the logs" instruction, and one that could not even be
        // created has redacted nothing: passing the body through untouched logged it in the clear
        BoundedBodyCapture capture = new BoundedBodyCapture(-1);
        capture.getSink().write("{\"password\":\"hunter2\"}".getBytes(UTF_8));

        // When
        String result = capture.getContent(factory.getInstances(List.of(UninstantiableBodyFilter.class)), null);

        // Then
        assertEquals(FILTERING_FAILURE_MARKER, result);
    }

    @Test
    @DisplayName("Check a body filter whose class fails to initialize is cached as failed rather than failing the caller")
    void checkFailedClassInitializationIsCachedAsFailed() {
        // A static initializer that throws - a pattern constant that does not compile - fails with an Error
        // rather than an Exception, the first time and every time after, which escaped every guard between
        // the filter and the exchange and failed every request to the resources declaring it
        Class<BrokenClassBodyFilter> type = BrokenClassBodyFilter.class;

        // When
        LoggedBodyFilter first = assertDoesNotThrow(() -> factory.getInstance(type));
        LoggedBodyFilter second = assertDoesNotThrow(() -> factory.getInstance(type));

        // Then
        assertSame(FAILED_BODY_FILTER, first);
        assertSame(first, second);
    }

    public static class BrokenClassBodyFilter implements LoggedBodyFilter {

        private static final Object BROKEN = fail();

        private static Object fail() {
            throw new IllegalStateException("Broken static initializer");
        }

        @Override
        public void filter(StringBuilder body) {
            // Never reached, the class never initializes
        }

    }

    static class UninstantiableBodyFilter implements LoggedBodyFilter {

        UninstantiableBodyFilter(String required) {
            // No no-arg constructor available on purpose
        }

        @Override
        public void filter(StringBuilder body) {
            // Never reached, instantiation always fails
        }

    }

}
