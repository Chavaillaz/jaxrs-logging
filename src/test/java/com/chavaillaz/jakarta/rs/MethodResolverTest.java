package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.RESPONSE;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.MDC;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static com.chavaillaz.jakarta.rs.capture.LoggedBodyCapture.DEFAULT_LIMIT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import jakarta.ws.rs.container.ResourceInfo;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.chavaillaz.jakarta.rs.MethodResolver.MethodConfiguration;
import com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyConfiguration;
import com.chavaillaz.jakarta.rs.internal.LoggedBodyFilterFactory;

/**
 * Exercises {@link MethodResolver} directly: resolution depends on a {@link ResourceInfo} alone, so none of
 * the request and response contexts {@link LoggedFeatureTest} mocks are needed here.
 */
@DisplayName("Method resolver")
@ExtendWith(MockitoExtension.class)
class MethodResolverTest extends AbstractFilterTest {

    @Mock
    ResourceInfo resourceInfo;

    private final MethodResolver resolver = new MethodResolver();

    // Used directly as the resource class and method, so each method's own annotations are found straight
    // away without walking the declaration sites above them (covered on its own, together with the priority
    // between those sites, by LoggedUtilsTest)
    interface Resource {

        @LoggedBody(MDC)
        void bothMethod();

        @LoggedBody(value = LOG, directions = REQUEST)
        void requestOnlyMethod();

        @LoggedBody(value = LOG, directions = RESPONSE)
        void responseOnlyMethod();

        @Logged
        @LoggedMapping(type = QUERY, mdcKey = "topic", paramNames = "topic")
        void mappedMethod();

        @Logged
        @LoggedMapping(type = QUERY, auto = true)
        @LoggedMapping(type = QUERY, mdcKey = "topic", paramNames = "topic")
        @LoggedMapping(type = QUERY, paramNames = "secret")
        void unorderedMappingsMethod();

        @LoggedBody(value = LOG, directions = {REQUEST, REQUEST})
        void repeatedDirectionMethod();

        @Logged({@LoggedBody(LOG), @LoggedBody(MDC)})
        void competingMethod();

        @Logged({@LoggedBody(value = LOG, directions = REQUEST), @LoggedBody(value = MDC, directions = REQUEST)})
        void competingRequestOnlyMethod();

        @LoggedBody(value = LOG, limit = -2)
        void invalidLimitMethod();

        @LoggedMapping(type = QUERY, mdcKey = "topic", paramNames = "topic")
        void singleMappingMethod();

        @LoggedMapping(type = QUERY, mdcKey = "topic", paramNames = "topic")
        @LoggedMapping(type = QUERY, mdcKey = "page", paramNames = "page")
        void repeatedMappingMethod();

        void plainMethod();

    }

    void setup(String methodName) throws Exception {
        doReturn(Resource.class).when(resourceInfo).getResourceClass();
        doReturn(Resource.class.getMethod(methodName)).when(resourceInfo).getResourceMethod();
    }

    @Test
    @DisplayName("Check a configuration for both directions applies to request and response")
    void checkBothDirectionsConfiguration() throws Exception {
        setup("bothMethod");

        MethodConfiguration configuration = resolver.resolve(resourceInfo);

        assertTrue(configuration.body(REQUEST).isActive());
        assertEquals(configuration.body(REQUEST), configuration.body(RESPONSE));
        assertTrue(configuration.capturesBodies());
    }

    @Test
    @DisplayName("Check a request-only configuration does not apply to the response")
    void checkRequestOnlyConfiguration() throws Exception {
        setup("requestOnlyMethod");

        MethodConfiguration configuration = resolver.resolve(resourceInfo);

        assertEquals(Set.of(LOG), configuration.body(REQUEST).types());
        assertFalse(configuration.body(RESPONSE).isActive());
    }

    @Test
    @DisplayName("Check a response-only configuration does not apply to the request")
    void checkResponseOnlyConfiguration() throws Exception {
        setup("responseOnlyMethod");

        MethodConfiguration configuration = resolver.resolve(resourceInfo);

        assertFalse(configuration.body(REQUEST).isActive());
        assertEquals(Set.of(LOG), configuration.body(RESPONSE).types());
    }

    @Test
    @DisplayName("Check a configuration naming its direction twice still applies to that direction")
    void checkRepeatedDirectionConfiguration() throws Exception {
        // Recognized by the number of directions it listed, it applied to neither, without a word
        setup("repeatedDirectionMethod");

        MethodConfiguration configuration = resolver.resolve(resourceInfo);

        assertEquals(Set.of(LOG), configuration.body(REQUEST).types());
        assertFalse(configuration.body(RESPONSE).isActive());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"competingMethod", "competingRequestOnlyMethod"})
    @DisplayName("Check the first of several configurations competing for a direction is the one applied")
    void checkFirstCompetingConfigurationApplied(String methodName) throws Exception {
        // The first one used to win among those for a single direction, and the last one among those for both
        setup(methodName);

        assertEquals(Set.of(LOG), resolver.resolve(resourceInfo).body(REQUEST).types());
    }

    @Test
    @DisplayName("Check a body configuration that cannot be resolved is reported, the method logging no body")
    void checkUnresolvableBodyConfiguration() throws Exception {
        setup("bothMethod");

        // Given: filters that cannot even be determined, as a @LoggedBody naming a class missing at runtime
        // throws a TypeNotPresentException the moment it is read
        MethodResolver failingResolver = new MethodResolver(new LoggedBodyFilterFactory(LoggedFeature.log) {

            @Override
            public List<LoggedBodyFilter> getInstances(Class<? extends LoggedBodyFilter>[] filterTypes) {
                throw new TypeNotPresentException("com.company.MissingFilter", null);
            }

        });

        // When
        MethodConfiguration configuration = assertDoesNotThrow(() -> failingResolver.resolve(resourceInfo));

        // Then: its redaction cannot be guaranteed, which is reported on the logger of the feature
        assertSame(LoggedBodyConfiguration.NONE, configuration.requestBody());
        assertSame(LoggedBodyConfiguration.NONE, configuration.responseBody());
        assertFalse(configuration.capturesBodies());
        LogEvent report = listAppender.findFirstMessage("Unable to resolve the body logging configuration of");
        assertNotNull(report);
        assertEquals(LoggedFeature.class.getName(), report.getLoggerName());
    }

    @Test
    @DisplayName("Check a body configuration with an invalid limit is reported as the method is resolved")
    void checkInvalidLimitConfiguration() throws Exception {
        // Accepted as resolved, it failed the creation of every capture it was given to, reported on every
        // single request to the resource, without naming the resource whose annotation had to be fixed
        setup("invalidLimitMethod");

        // When
        MethodConfiguration configuration = assertDoesNotThrow(() -> resolver.resolve(resourceInfo));

        // Then
        assertSame(LoggedBodyConfiguration.NONE, configuration.requestBody());
        assertSame(LoggedBodyConfiguration.NONE, configuration.responseBody());
        assertThrows(IllegalArgumentException.class, () -> new LoggedBodyConfiguration(Set.of(LOG), -2, List.of()));
    }

    @Test
    @DisplayName("Check a body configuration declaring no limit is given the default one")
    void checkDefaultLimit() throws Exception {
        // Without a limit, a body was captured whole, however large the client made it
        setup("bothMethod");

        // When
        MethodConfiguration configuration = resolver.resolve(resourceInfo);

        // Then
        assertEquals(DEFAULT_LIMIT, configuration.body(REQUEST).limit());
        assertEquals(DEFAULT_LIMIT, configuration.body(RESPONSE).limit());
    }

    @Test
    @DisplayName("Check merged mappings are resolved for the resource method")
    void checkMergedMappings() throws Exception {
        setup("mappedMethod");

        List<LoggedMapping> mappings = resolver.resolve(resourceInfo).mappings();

        assertEquals(1, mappings.size());
        assertEquals("topic", mappings.getFirst().mdcKey());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"competingMethod", "bothMethod", "repeatedDirectionMethod", "singleMappingMethod", "repeatedMappingMethod"})
    @DisplayName("Check a resource method declaring an annotation of this library, once or repeated, is logged")
    void checkAnnotatedMethodLogged(String methodName) throws Exception {
        setup(methodName);

        assertTrue(resolver.isLogged(resourceInfo));
    }

    @Test
    @DisplayName("Check a resource method declaring no annotation of this library is not logged")
    void checkPlainMethodNotLogged() throws Exception {
        setup("plainMethod");

        assertFalse(resolver.isLogged(resourceInfo));
    }

    @Test
    @DisplayName("Check a resource whose declaration sites cannot be walked is reported, and not logged")
    void checkUnresolvableResourceNotLogged() {
        // Given: the resource method failing to be read, as reflection parsing a generic signature that names a
        // type missing at runtime throws
        doReturn(Resource.class).when(resourceInfo).getResourceClass();
        doThrow(new TypeNotPresentException("com.company.MissingType", null)).when(resourceInfo).getResourceMethod();

        // Then
        assertFalse(assertDoesNotThrow(() -> resolver.isLogged(resourceInfo)));
        assertNotNull(listAppender.findFirstMessage("Unable to find out whether the requests of"));
    }

    @Test
    @DisplayName("Check a resource without class nor method is not logged")
    void checkUnmatchedResourceNotLogged() {
        assertFalse(resolver.isLogged(resourceInfo));
    }

    @Test
    @DisplayName("Check mappings that cannot be resolved are reported, the method mapping nothing")
    void checkUnresolvableMappings() {
        // Given: a resource whose declaration sites cannot be walked
        doReturn(Resource.class).when(resourceInfo).getResourceClass();
        doThrow(new TypeNotPresentException("com.company.MissingType", null)).when(resourceInfo).getResourceMethod();

        // When
        MethodConfiguration configuration = assertDoesNotThrow(() -> resolver.resolve(resourceInfo));

        // Then
        assertTrue(configuration.mappings().isEmpty());
        assertNotNull(listAppender.findFirstMessage("Unable to resolve the MDC mappings of"));
    }

    @Test
    @DisplayName("Check mappings are resolved in the order they apply in, whatever the order they are declared in")
    void checkMappingsInApplicationOrder() throws Exception {
        setup("unorderedMappingsMethod");

        List<LoggedMapping> mappings = resolver.resolve(resourceInfo).mappings();

        // The exclusion first, so no other mapping maps what it excludes, and the automatic mapping last,
        // so it only maps what no explicit one claimed
        assertEquals(List.of("", "topic", ""), mappings.stream().map(LoggedMapping::mdcKey).toList());
        assertEquals(List.of(false, false, true), mappings.stream().map(LoggedMapping::auto).toList());
    }

    @Test
    @DisplayName("Check a resource without method logs no body")
    void checkNullResourceMethodBodyConfiguration() {
        doReturn(null).when(resourceInfo).getResourceMethod();

        MethodConfiguration configuration = resolver.resolve(resourceInfo);

        assertFalse(configuration.body(REQUEST).isActive());
        assertFalse(configuration.body(RESPONSE).isActive());
    }

    @Test
    @DisplayName("Check a resource without method still resolves its mappings from the class")
    void checkNullResourceMethodMergedMappings() {
        doReturn(Resource.class).when(resourceInfo).getResourceClass();
        doReturn(null).when(resourceInfo).getResourceMethod();

        // The interface declares its mappings on methods only, so nothing applies without one
        assertTrue(resolver.resolve(resourceInfo).mappings().isEmpty());
    }

    @Test
    @DisplayName("Check two resource classes sharing the same interface method are resolved each its own way")
    void checkResolutionIncludesResourceClass() throws Exception {
        // Given: two resource classes whose method is the very same java.lang.reflect.Method, as a container
        // handing out the interface method for each implementation would produce
        ResourceInfo first = resourceInfo(SharedInterfaceResource.class);
        ResourceInfo second = resourceInfo(OtherSharedInterfaceResource.class);

        // When
        LoggedBodyConfiguration firstConfiguration = resolver.resolve(first).body(REQUEST);
        LoggedBodyConfiguration secondConfiguration = resolver.resolve(second).body(REQUEST);

        // Then
        assertEquals(Set.of(MDC), firstConfiguration.types());
        assertEquals(Set.of(LOG), secondConfiguration.types());
    }

    private ResourceInfo resourceInfo(Class<?> resourceClass) throws Exception {
        Method method = SharedInterface.class.getMethod("shared");
        return new ResourceInfo() {

            @Override
            public Method getResourceMethod() {
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
