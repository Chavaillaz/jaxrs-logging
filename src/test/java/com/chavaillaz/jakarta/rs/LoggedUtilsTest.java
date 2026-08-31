package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.MDC;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getAnnotation;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getMergedMappings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("Logged utils")
@ExtendWith(MockitoExtension.class)
class LoggedUtilsTest {

    @Mock
    ResourceInfo resourceInfo;

    interface MappingInterface {

        // Repeated mappings declared on an interface method (not the concrete class)
        @LoggedMapping(type = QUERY, mdcKey = "interface-a", paramNames = "a")
        @LoggedMapping(type = HEADER, mdcKey = "interface-b", paramNames = "b")
        void mapped();

    }

    // Single (non-repeated) mapping declared directly on the concrete resource class
    @LoggedMapping(type = QUERY, mdcKey = "class-level", paramNames = "single")
    static class MappingResource implements MappingInterface {

        @Override
        public void mapped() {
            // No-op
        }

    }

    @Test
    @DisplayName("Check mappings are found regardless of being single or repeated, and on the class or an interface")
    void checkMergedMappings() throws Exception {
        // Given
        doReturn(MappingResource.class).when(resourceInfo).getResourceClass();
        doReturn(MappingResource.class.getMethod("mapped")).when(resourceInfo).getResourceMethod();

        // When
        Set<LoggedMapping> mappings = getMergedMappings(resourceInfo);

        // Then
        Set<String> mdcKeys = mappings.stream().map(LoggedMapping::mdcKey).collect(Collectors.toSet());
        assertEquals(3, mappings.size());
        assertTrue(mdcKeys.contains("interface-a"));
        assertTrue(mdcKeys.contains("interface-b"));
        assertTrue(mdcKeys.contains("class-level"));
    }

    // Class-level configuration applying (by default) to both request and response
    @Logged(@LoggedBody(MDC))
    interface ConflictingAnnotationsInterface {

        // Method-level configuration for the same method, applying to the request only
        @LoggedBody(value = LOG, targets = REQUEST)
        void method();

    }

    // Does not redeclare the annotation on its own override, so it can only be found by walking
    // up to the interface - where it exists at both the method and the class level
    static class ConflictingAnnotationsResource implements ConflictingAnnotationsInterface {

        @Override
        public void method() {
            // No-op
        }

    }

    // The shape every real JAX-RS resource has: configuration on the class, JAX-RS annotations (and
    // nothing else) on the method
    @Path("/article")
    @Logged(@LoggedBody(MDC))
    static class ClassLevelResource {

        @POST
        @Path("/create")
        public String create(String article) {
            return article;
        }

    }

    @Test
    @DisplayName("Check a class-level configuration applies to a method carrying its own JAX-RS annotations")
    void checkClassLevelAnnotationFoundForAnnotatedMethod() throws Exception {
        // Given
        doReturn(ClassLevelResource.class).when(resourceInfo).getResourceClass();
        doReturn(ClassLevelResource.class.getMethod("create", String.class)).when(resourceInfo).getResourceMethod();

        // When
        List<LoggedBody> result = getAnnotation(resourceInfo, LoggedBody.class, Logged.class, Logged::value);

        // Then: the method's own @POST/@Path must not hide the class-level configuration, as they used
        // to by making the resolution stop at the (empty) interface level
        assertEquals(1, result.size());
        assertEquals(Set.of(MDC), Set.of(result.getFirst().value()));
    }

    @Test
    @DisplayName("Check a method-level annotation on an implemented interface takes priority over that interface's class-level annotation")
    void checkMethodLevelAnnotationTakesPriorityOverInterfaceClassLevel() throws Exception {
        // Given
        doReturn(ConflictingAnnotationsResource.class).when(resourceInfo).getResourceClass();
        doReturn(ConflictingAnnotationsResource.class.getMethod("method")).when(resourceInfo).getResourceMethod();

        // When
        List<LoggedBody> result = getAnnotation(resourceInfo, LoggedBody.class, Logged.class, Logged::value);

        // Then: only the method-level LoggedBody(LOG, REQUEST) is found, not merged with the
        // interface's class-level LoggedBody(MDC, both)
        assertEquals(1, result.size());
        assertEquals(Set.of(LOG), Set.of(result.getFirst().value()));
        assertEquals(Set.of(REQUEST), Set.of(result.getFirst().targets()));
    }

}
