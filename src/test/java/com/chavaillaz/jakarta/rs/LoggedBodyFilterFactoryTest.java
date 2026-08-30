package com.chavaillaz.jakarta.rs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("Check getInstances flattens filter classes declared over multiple annotations")
    void checkInstancesFlattensMultipleAnnotations() {
        // Given
        Stream<Class<? extends LoggedBodyFilter>[]> filterTypes = Stream.of(
                new Class[]{SensitiveBodyFilter.class},
                new Class[]{SensitiveBodyFilter.class});

        // When
        Set<LoggedBodyFilter> instances = factory.getInstances(filterTypes);

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
            Stream<Class<? extends LoggedBodyFilter>[]> filterTypes = Stream.of(
                    new Class[]{AppendA.class}, new Class[]{AppendB.class});
            Set<LoggedBodyFilter> instances = factory.getInstances(filterTypes);
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

        // Then: the same no-op sentinel is returned both times, from a single cache entry,
        // instead of reflection (and the accompanying error log) being retried on every call
        assertSame(first, second);
        assertEquals(1, factory.cache.size());
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
