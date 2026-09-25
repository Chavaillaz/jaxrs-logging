package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.MDC;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import jakarta.ws.rs.container.ResourceInfo;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.chavaillaz.jakarta.rs.LoggedResolver.BodyConfiguration;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyConfiguration;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyFilterFactory;

/**
 * Exercises {@link LoggedResolver} directly, without going through {@link LoggedFilter}: since
 * resolution only depends on a {@link ResourceInfo}, none of the request/response context mocking
 * used by {@link LoggedFilterTest} is needed here.
 */
@DisplayName("Logged resolver")
@ExtendWith(MockitoExtension.class)
class LoggedResolverTest {

    @Mock
    ResourceInfo resourceInfo;

    private final LoggedResolver resolver = new LoggedResolver();

    // Used directly as the resource class/method (like LoggedFilterTest's AnnotatedResource), so each
    // method's own annotations are found straight away without walking the declaration sites above them
    // (covered on its own, together with the priority between those sites, by LoggedUtilsTest)
    interface Resource {

        @LoggedBody(MDC)
        void bothMethod();

        @LoggedBody(value = LOG, targets = REQUEST)
        void requestOnlyMethod();

        @LoggedBody(value = LOG, targets = RESPONSE)
        void responseOnlyMethod();

        @Logged
        @LoggedMapping(type = QUERY, mdcKey = "topic", paramNames = "topic")
        void mappedMethod();

        @Logged
        @LoggedMapping(type = QUERY, auto = true)
        @LoggedMapping(type = QUERY, mdcKey = "topic", paramNames = "topic")
        @LoggedMapping(type = QUERY, paramNames = "secret")
        void unorderedMappingsMethod();

        @LoggedBody(value = LOG, targets = {REQUEST, REQUEST})
        void repeatedTargetMethod();

        @Logged({@LoggedBody(LOG), @LoggedBody(MDC)})
        void competingMethod();

        @Logged({@LoggedBody(value = LOG, targets = REQUEST), @LoggedBody(value = MDC, targets = REQUEST)})
        void competingRequestOnlyMethod();

        @LoggedBody(value = LOG, limit = -2)
        void invalidLimitMethod();

    }

    void setup(String methodName) throws Exception {
        doReturn(Resource.class).when(resourceInfo).getResourceClass();
        doReturn(Resource.class.getMethod(methodName)).when(resourceInfo).getResourceMethod();
    }

    @Test
    @DisplayName("Check a configuration targeting both directions applies to request and response")
    void checkBothDirectionsConfiguration() throws Exception {
        setup("bothMethod");

        LoggedBodyConfiguration request = resolver.getBodyConfiguration(resourceInfo, REQUEST);
        LoggedBodyConfiguration response = resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertTrue(request.isActive());
        assertEquals(request, response);
    }

    @Test
    @DisplayName("Check a request-only configuration does not apply to the response")
    void checkRequestOnlyConfiguration() throws Exception {
        setup("requestOnlyMethod");

        LoggedBodyConfiguration request = resolver.getBodyConfiguration(resourceInfo, REQUEST);
        LoggedBodyConfiguration response = resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertEquals(Set.of(LOG), request.types());
        assertFalse(response.isActive());
    }

    @Test
    @DisplayName("Check a response-only configuration does not apply to the request")
    void checkResponseOnlyConfiguration() throws Exception {
        setup("responseOnlyMethod");

        LoggedBodyConfiguration request = resolver.getBodyConfiguration(resourceInfo, REQUEST);
        LoggedBodyConfiguration response = resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertFalse(request.isActive());
        assertEquals(Set.of(LOG), response.types());
    }

    @Test
    @DisplayName("Check a configuration naming its direction twice still applies to that direction")
    void checkRepeatedTargetConfiguration() throws Exception {
        // Recognized by the number of directions it listed, it applied to neither, without a word
        setup("repeatedTargetMethod");

        LoggedBodyConfiguration request = resolver.getBodyConfiguration(resourceInfo, REQUEST);
        LoggedBodyConfiguration response = resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertEquals(Set.of(LOG), request.types());
        assertFalse(response.isActive());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"competingMethod", "competingRequestOnlyMethod"})
    @DisplayName("Check the first of several configurations competing for a direction is the one applied")
    void checkFirstCompetingConfigurationApplied(String methodName) throws Exception {
        // The first one used to win among those targeting a single direction, and the last one among those
        // targeting both
        setup(methodName);

        LoggedBodyConfiguration request = resolver.getBodyConfiguration(resourceInfo, REQUEST);

        assertEquals(Set.of(LOG), request.types());
    }

    @Test
    @DisplayName("Check body configuration is resolved once per resource method and cached")
    void checkBodyConfigurationCaching() throws Exception {
        setup("bothMethod");

        resolver.getBodyConfiguration(resourceInfo, REQUEST);
        resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertEquals(1, resolver.bodyConfigurationCache.size());
    }

    @Test
    @DisplayName("Check a body configuration that cannot be resolved logs no body, and is not resolved again")
    void checkUnresolvableBodyConfiguration() throws Exception {
        setup("bothMethod");

        // Given: filters that cannot even be determined, as a @LoggedBody naming a class missing at runtime
        // throws a TypeNotPresentException the moment it is read
        AtomicInteger attempts = new AtomicInteger();
        LoggedResolver failingResolver = new LoggedResolver(new LoggedBodyFilterFactory() {

            @Override
            public Set<LoggedBodyFilter> getInstances(Class<? extends LoggedBodyFilter>[] filterTypes) {
                attempts.incrementAndGet();
                throw new TypeNotPresentException("com.company.MissingFilter", null);
            }

        });

        // When
        BodyConfiguration first = assertDoesNotThrow(() -> failingResolver.getBodyConfiguration(resourceInfo));
        BodyConfiguration second = failingResolver.getBodyConfiguration(resourceInfo);

        // Then: no later request resolves it any better, so it is neither retried nor logged each time
        assertSame(BodyConfiguration.NONE, first);
        assertSame(first, second);
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("Check a body configuration with an invalid limit is rejected once, rather than on every request")
    void checkInvalidLimitConfiguration() throws Exception {
        // Accepted as resolved, it failed the creation of every capture it was given to, reported on every
        // single request to the resource, without naming the resource whose annotation had to be fixed
        setup("invalidLimitMethod");

        // When
        BodyConfiguration configuration = assertDoesNotThrow(() -> resolver.getBodyConfiguration(resourceInfo));

        // Then
        assertSame(BodyConfiguration.NONE, configuration);
        assertThrows(IllegalArgumentException.class, () -> new LoggedBodyConfiguration(Set.of(LOG), -2, Set.of()));
    }

    @Test
    @DisplayName("Check merged mappings are resolved for the matched resource method")
    void checkMergedMappings() throws Exception {
        setup("mappedMethod");

        List<LoggedMapping> mappings = resolver.getMappings(resourceInfo);

        assertEquals(1, mappings.size());
        assertEquals("topic", mappings.iterator().next().mdcKey());
    }

    @Test
    @DisplayName("Check merged mappings are resolved once per resource method and cached")
    void checkMergedMappingsCaching() throws Exception {
        setup("mappedMethod");

        List<LoggedMapping> first = resolver.getMappings(resourceInfo);
        List<LoggedMapping> second = resolver.getMappings(resourceInfo);

        assertEquals(first, second);
        assertEquals(1, resolver.mappingsCache.size());
    }

    @Test
    @DisplayName("Check mappings are resolved in the order they apply in, whatever the order they are declared in")
    void checkMappingsInApplicationOrder() throws Exception {
        setup("unorderedMappingsMethod");

        List<LoggedMapping> mappings = resolver.getMappings(resourceInfo);

        // The exclusion first, so no other mapping maps what it excludes, and the automatic mapping last,
        // so it only maps what no explicit one claimed
        assertEquals(List.of("", "topic", ""), mappings.stream().map(LoggedMapping::mdcKey).toList());
        assertEquals(List.of(false, false, true), mappings.stream().map(LoggedMapping::auto).toList());
    }

    @Test
    @DisplayName("Check a null resource method (some containers can still hand one out) does not throw")
    void checkNullResourceMethodBodyConfiguration() {
        doReturn(null).when(resourceInfo).getResourceMethod();

        LoggedBodyConfiguration request = resolver.getBodyConfiguration(resourceInfo, REQUEST);
        LoggedBodyConfiguration response = resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertFalse(request.isActive());
        assertFalse(response.isActive());
        assertEquals(0, resolver.bodyConfigurationCache.size());
    }

    @Test
    @DisplayName("Check a null resource method (some containers can still hand one out) still resolves from the class")
    void checkNullResourceMethodMergedMappings() {
        doReturn(Resource.class).when(resourceInfo).getResourceClass();
        doReturn(null).when(resourceInfo).getResourceMethod();

        List<LoggedMapping> mappings = resolver.getMappings(resourceInfo);

        // The interface declares its mappings on methods only, so nothing applies without one, but the
        // class is still a valid cache key: only a resource with neither a class nor a method is skipped
        assertTrue(mappings.isEmpty());
        assertEquals(1, resolver.mappingsCache.size());
    }

    @Test
    @DisplayName("Check the cache distinguishes two resource classes sharing the same interface method")
    void checkCacheKeyIncludesResourceClass() throws Exception {
        // Given: two resource classes whose matched method is the very same java.lang.reflect.Method,
        // as a container handing out the interface method for each implementation would produce
        ResourceInfo first = resourceInfo(SharedInterfaceResource.class);
        ResourceInfo second = resourceInfo(OtherSharedInterfaceResource.class);

        // When
        LoggedBodyConfiguration firstConfiguration = resolver.getBodyConfiguration(first, REQUEST);
        LoggedBodyConfiguration secondConfiguration = resolver.getBodyConfiguration(second, REQUEST);

        // Then: each class gets its own configuration instead of the first one resolved winning for both
        assertEquals(Set.of(MDC), firstConfiguration.types());
        assertEquals(Set.of(LOG), secondConfiguration.types());
        assertEquals(2, resolver.bodyConfigurationCache.size());
    }

    private ResourceInfo resourceInfo(Class<?> resourceClass) throws Exception {
        java.lang.reflect.Method method = SharedInterface.class.getMethod("shared");
        return new ResourceInfo() {

            @Override
            public java.lang.reflect.Method getResourceMethod() {
                return method;
            }

            @Override
            public Class<?> getResourceClass() {
                return resourceClass;
            }

        };
    }

    interface SharedInterface {

        void shared();

    }

    @Logged(@LoggedBody(MDC))
    static class SharedInterfaceResource implements SharedInterface {

        @Override
        public void shared() {
            // No-op
        }

    }

    @Logged(@LoggedBody(LOG))
    static class OtherSharedInterfaceResource implements SharedInterface {

        @Override
        public void shared() {
            // No-op
        }

    }

}
