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

import java.util.Optional;
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
    // method's own annotations are found straight away without going through the interface-fallback
    // path in LoggedUtils.getAnnotationsInterfaces (covered on its own, together with the priority it
    // gives a method-level annotation over an interface's class-level one, by LoggedUtilsTest)
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

        Optional<LoggedBody> request = resolver.getBodyConfiguration(resourceInfo, REQUEST);
        Optional<LoggedBody> response = resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertTrue(request.isPresent());
        assertEquals(request.get(), response.get());
    }

    @Test
    @DisplayName("Check a request-only configuration does not apply to the response")
    void checkRequestOnlyConfiguration() throws Exception {
        setup("requestOnlyMethod");

        Optional<LoggedBody> request = resolver.getBodyConfiguration(resourceInfo, REQUEST);
        Optional<LoggedBody> response = resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertTrue(request.isPresent());
        assertEquals(Set.of(LOG), Set.of(request.get().value()));
        assertFalse(response.isPresent());
    }

    @Test
    @DisplayName("Check a response-only configuration does not apply to the request")
    void checkResponseOnlyConfiguration() throws Exception {
        setup("responseOnlyMethod");

        Optional<LoggedBody> request = resolver.getBodyConfiguration(resourceInfo, REQUEST);
        Optional<LoggedBody> response = resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertFalse(request.isPresent());
        assertTrue(response.isPresent());
        assertEquals(Set.of(LOG), Set.of(response.get().value()));
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

        Optional<LoggedBody> request = resolver.getBodyConfiguration(resourceInfo, REQUEST);
        Optional<LoggedBody> response = resolver.getBodyConfiguration(resourceInfo, RESPONSE);

        assertFalse(request.isPresent());
        assertFalse(response.isPresent());
        assertEquals(0, resolver.bodyConfigurationCache.size());
    }

    @Test
    @DisplayName("Check a null resource method (some containers can still hand one out) does not throw")
    void checkNullResourceMethodMergedMappings() {
        doReturn(Resource.class).when(resourceInfo).getResourceClass();
        doReturn(null).when(resourceInfo).getResourceMethod();

        Set<LoggedMapping> mappings = resolver.getMergedMappings(resourceInfo);

        assertTrue(mappings.isEmpty());
        assertEquals(0, resolver.mappingsCache.size());
    }

}
