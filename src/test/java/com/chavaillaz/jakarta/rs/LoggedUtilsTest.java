package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedBody.Direction.REQUEST;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.LOG;
import static com.chavaillaz.jakarta.rs.LoggedBody.LogType.MDC;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.QUERY;
import static com.chavaillaz.jakarta.rs.LoggedUtils.declarationSites;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getAnnotation;
import static com.chavaillaz.jakarta.rs.LoggedUtils.getMergedMappings;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ResourceInfo;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        Set<String> mdcKeys = mappings.stream().map(LoggedMapping::mdcKey).collect(toSet());
        assertEquals(3, mappings.size());
        assertTrue(mdcKeys.contains("interface-a"));
        assertTrue(mdcKeys.contains("interface-b"));
        assertTrue(mdcKeys.contains("class-level"));
    }

    // The class maps the header and the query parameter the method maps, in other casings, under keys
    // sorting first: only the declaration priority, not the order mappings are applied in, can make the
    // method-level ones win
    @LoggedMapping(type = HEADER, mdcKey = "agent", paramNames = "user-agent")
    @LoggedMapping(type = QUERY, mdcKey = "class-topic", paramNames = "Topic")
    static class CasingResource {

        @LoggedMapping(type = HEADER, mdcKey = "ua", paramNames = "User-Agent")
        @LoggedMapping(type = QUERY, mdcKey = "topic", paramNames = "topic")
        public void method() {
            // No-op
        }

    }

    @Test
    @DisplayName("Check a header mapped at two levels in different casings is only mapped by the most specific one")
    void checkHeaderMappingsMergedWhateverTheirCasing() throws Exception {
        // Given
        doReturn(CasingResource.class).when(resourceInfo).getResourceClass();
        doReturn(CasingResource.class.getMethod("method")).when(resourceInfo).getResourceMethod();

        // When
        Set<LoggedMapping> mappings = getMergedMappings(resourceInfo);

        // Then: header names are case-insensitive, query parameter names are not
        assertEquals(Set.of("ua", "topic", "class-topic"), mappings.stream()
                .map(LoggedMapping::mdcKey)
                .collect(toSet()));
    }

    // The class and the method both map every header automatically, under prefixes of their own
    @LoggedMapping(type = HEADER, auto = true, mdcPrefix = "class-")
    static class AutomaticResource {

        @LoggedMapping(type = HEADER, auto = true, mdcPrefix = "method-")
        @LoggedMapping(type = QUERY, auto = true, mdcPrefix = "query-")
        public void method() {
            // No-op
        }

    }

    @Test
    @DisplayName("Check a type of parameter mapped automatically at two levels is only mapped by the most specific one")
    void checkAutomaticMappingsMerged() throws Exception {
        // Given
        doReturn(AutomaticResource.class).when(resourceInfo).getResourceClass();
        doReturn(AutomaticResource.class.getMethod("method")).when(resourceInfo).getResourceMethod();

        // When
        Set<LoggedMapping> mappings = getMergedMappings(resourceInfo);

        // Then: declaring no parameter name, both automatic header mappings would otherwise map every header
        assertEquals(Set.of("method-", "query-"), mappings.stream()
                .map(LoggedMapping::mdcPrefix)
                .collect(toSet()));
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

    interface CrudApi<T> {

        @Logged(@LoggedBody(LOG))
        void create(T entity);

    }

    // Implements the generic method for its own type argument, and overloads it for another type, which
    // implements nothing
    static class ArticleCrudResource implements CrudApi<String> {

        @Override
        public void create(String article) {
            // No-op
        }

        public void create(Integer id) {
            // No-op
        }

    }

    abstract static class AbstractCrudResource<E> implements CrudApi<E> {

    }

    // Gives its type argument to the interface through a generic superclass
    static class NoteCrudResource extends AbstractCrudResource<String> {

        @Override
        public void create(String note) {
            // No-op
        }

    }

    @ParameterizedTest
    @ValueSource(classes = {ArticleCrudResource.class, NoteCrudResource.class})
    @DisplayName("Check an annotation on a generic interface method is found for the method implementing it")
    void checkGenericInterfaceMethodAnnotationFound(Class<?> resourceClass) throws Exception {
        // Given
        doReturn(resourceClass).when(resourceInfo).getResourceClass();
        doReturn(resourceClass.getMethod("create", String.class)).when(resourceInfo).getResourceMethod();

        // When
        List<LoggedBody> result = getAnnotation(resourceInfo, LoggedBody.class, Logged.class, Logged::value);

        // Then: create(String) implements create(T), although the interface method erases to create(Object)
        assertEquals(1, result.size());
        assertEquals(Set.of(LOG), Set.of(result.getFirst().value()));
    }

    @Test
    @DisplayName("Check an annotation on a generic interface method is not found for an overload implementing nothing")
    void checkGenericInterfaceMethodAnnotationIgnoredForOverload() throws Exception {
        // Given
        doReturn(ArticleCrudResource.class).when(resourceInfo).getResourceClass();
        doReturn(ArticleCrudResource.class.getMethod("create", Integer.class)).when(resourceInfo).getResourceMethod();

        // When
        List<LoggedBody> result = getAnnotation(resourceInfo, LoggedBody.class, Logged.class, Logged::value);

        // Then
        assertTrue(result.isEmpty());
    }

    interface Api {

        void read(String id);

    }

    abstract static class BaseResource {

        public abstract void read(String id);

        public void list() {
            // Inherited by the resource classes as it is
        }

    }

    abstract static class GenericBaseResource<E> extends BaseResource {

        public abstract void save(E entity);

        public abstract void saveAll(E[] entities);

    }

    static class ConcreteResource extends GenericBaseResource<String> implements Api {

        @Override
        public void read(String id) {
            // No-op
        }

        @Override
        public void save(String entity) {
            // No-op
        }

        @Override
        public void saveAll(String[] entities) {
            // No-op
        }

        public void saveAll(Integer[] ids) {
            // Overloads the method above, overriding nothing
        }

    }

    @Test
    @DisplayName("Check the declaration sites of a method put the ones of the superclasses above those of the interfaces")
    void checkDeclarationSitesWithSuperclasses() throws Exception {
        // When
        List<AnnotatedElement> sites = declarationSites(ConcreteResource.class, ConcreteResource.class.getMethod("read", String.class));

        // Then: the methods overridden first, the superclasses' before the interfaces', then the types
        assertEquals(List.of(
                ConcreteResource.class.getMethod("read", String.class),
                BaseResource.class.getMethod("read", String.class),
                Api.class.getMethod("read", String.class),
                Api.class,
                ConcreteResource.class,
                GenericBaseResource.class,
                BaseResource.class), sites);
    }

    @Test
    @DisplayName("Check a method overriding a generic method of a superclass has it as a declaration site")
    void checkDeclarationSitesWithGenericSuperclass() throws Exception {
        // When: save(String) overrides save(E), which erases to save(Object)
        List<AnnotatedElement> sites = declarationSites(ConcreteResource.class, ConcreteResource.class.getMethod("save", String.class));

        // Then
        assertTrue(sites.contains(GenericBaseResource.class.getMethod("save", Object.class)), sites::toString);
    }

    @Test
    @DisplayName("Check a method overriding a generic method taking an array has it as a declaration site")
    void checkDeclarationSitesWithGenericArray() throws Exception {
        // When: saveAll(String[]) overrides saveAll(E[]), which erases to saveAll(Object[])
        Method overridden = GenericBaseResource.class.getMethod("saveAll", Object[].class);
        List<AnnotatedElement> sites = declarationSites(ConcreteResource.class, ConcreteResource.class.getMethod("saveAll", String[].class));
        List<AnnotatedElement> overloadSites = declarationSites(ConcreteResource.class, ConcreteResource.class.getMethod("saveAll", Integer[].class));

        // Then: the overload taking another array overrides nothing
        assertTrue(sites.contains(overridden), sites::toString);
        assertFalse(overloadSites.contains(overridden), overloadSites::toString);
    }

    @Test
    @DisplayName("Check a method inherited from a superclass as it is is its own declaration site, once")
    void checkDeclarationSitesOfInheritedMethod() throws Exception {
        // When: the container matches the method the superclass declares
        Method inherited = BaseResource.class.getMethod("list");
        List<AnnotatedElement> sites = declarationSites(ConcreteResource.class, inherited);

        // Then
        assertEquals(inherited, sites.getFirst());
        assertEquals(1, sites.stream().filter(inherited::equals).count());
    }

    @Test
    @DisplayName("Check a wrapper type given without its mapper, or the other way round, is rejected")
    void checkWrapperWithoutMapperRejected() {
        assertThrows(IllegalArgumentException.class, () -> getAnnotation(resourceInfo, LoggedBody.class, Logged.class, null));
        assertThrows(IllegalArgumentException.class, () -> getAnnotation(resourceInfo, LoggedBody.class, null, Logged::value));
    }

}
