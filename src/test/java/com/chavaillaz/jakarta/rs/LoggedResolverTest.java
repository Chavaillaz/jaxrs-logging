package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.MDC;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import java.util.Set;

import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    @DisplayName("Check body configuration is resolved once per resource method and cached")
    void checkBodyConfigurationCaching() throws Exception {
        setup("bothMethod");

        resolver.getBodyConfiguration(resourceInfo, REQUEST);
        resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertEquals(1, resolver.bodyConfigurationCache.size());
    }

    @Test
    @DisplayName("Check merged mappings are resolved for the matched resource method")
    void checkMergedMappings() throws Exception {
        setup("mappedMethod");

        Set<LoggedMapping> mappings = resolver.getMergedMappings(resourceInfo);

        assertEquals(1, mappings.size());
        assertEquals("topic", mappings.iterator().next().mdcKey());
    }

    @Test
    @DisplayName("Check merged mappings are resolved once per resource method and cached")
    void checkMergedMappingsCaching() throws Exception {
        setup("mappedMethod");

        Set<LoggedMapping> first = resolver.getMergedMappings(resourceInfo);
        Set<LoggedMapping> second = resolver.getMergedMappings(resourceInfo);

        assertEquals(first, second);
        assertEquals(1, resolver.mappingsCache.size());
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

        Set<LoggedMapping> mappings = resolver.getMergedMappings(resourceInfo);

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
