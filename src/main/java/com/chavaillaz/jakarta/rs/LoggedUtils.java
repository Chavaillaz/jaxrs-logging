package com.chavaillaz.jakarta.rs;

import static java.util.Arrays.asList;
import static org.apache.commons.lang3.ArrayUtils.containsAny;
import static org.apache.commons.lang3.ClassUtils.getAllInterfaces;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import jakarta.ws.rs.container.ResourceInfo;

/**
 * Utility class for logging providers.
 */
public class LoggedUtils {

    private LoggedUtils() {
        // Utility class
    }

    /**
     * Merges the different LoggedMapping annotations from any LoggedMappings within the class or its interfaces.
     *
     * @param resourceInfo The instance to access resource class and method
     * @return The set of merged LoggedMapping annotations
     */
    public static Set<LoggedMapping> getMergedMappings(ResourceInfo resourceInfo) {
        Set<LoggedMapping> mergedMappings = new HashSet<>();
        Class<?> resourceClass = resourceInfo.getResourceClass();
        Method resourceMethod = resourceInfo.getResourceMethod();

        // Priority: Method annotations > Interfaces annotations > Class annotation
        // Uses getAnnotationsByType() throughout, as it is the only lookup that correctly finds a
        // @LoggedMapping regardless of whether it is declared once or repeated (java.lang.annotation.Repeatable
        // only synthesizes the @LoggedMappings container when 2+ instances are present, so a single
        // annotation would be missed by a plain getAnnotation(LoggedMappings.class) lookup).
        // resourceMethod can be null for a container that does not resolve it at this stage (see
        // LoggedFilter#filter's own null-safe handling of it); areMethodsEqual below already tolerates
        // it, so only the direct annotation lookup on the method itself needs its own guard.
        if (resourceMethod != null) {
            mergeMappings(mergedMappings, resourceMethod.getAnnotationsByType(LoggedMapping.class));
        }
        for (Class<?> interfaceClass : getAllInterfaces(resourceClass)) {
            for (Method interfaceMethod : interfaceClass.getMethods()) {
                if (areMethodsEqual(interfaceMethod, resourceMethod)) {
                    mergeMappings(mergedMappings, interfaceMethod.getAnnotationsByType(LoggedMapping.class));
                }
            }
            mergeMappings(mergedMappings, interfaceClass.getAnnotationsByType(LoggedMapping.class));
        }
        mergeMappings(mergedMappings, resourceClass.getAnnotationsByType(LoggedMapping.class));

        return mergedMappings;
    }

    /**
     * Adds the given mappings to the merged mappings if they are not already present.
     *
     * @param mergedMappings The set of merged mappings
     * @param mappings       The mappings to add
     */
    public static void mergeMappings(Set<LoggedMapping> mergedMappings, LoggedMapping... mappings) {
        Arrays.stream(mappings)
                .filter(newMapping -> mergedMappings.stream()
                        .noneMatch(existingMapping -> newMapping.type() == existingMapping.type()
                                && containsAny(newMapping.paramNames(), (Object[]) existingMapping.paramNames())))
                .forEach(mergedMappings::add);
    }

    /**
     * Gets the given annotation from the resource method, its interfaces or its class matched by the current request.
     *
     * @param resourceInfo   The instance to access resource class and method
     * @param annotationType The annotation type to get
     * @param <A>            The annotation type
     * @return The annotation found or {@link Optional#empty} otherwise
     */
    public static <A extends Annotation> List<A> getAnnotation(ResourceInfo resourceInfo, Class<A> annotationType) {
        return getAnnotation(resourceInfo, annotationType, null, null);
    }

    /**
     * Gets the given annotation from the resource method, its interfaces or its class matched by the current request.
     * <p>
     * Note that if the resource class implements <em>multiple</em> interfaces which each declare a conflicting
     * annotation of this type for the same method, which one is returned is unspecified (it depends on the
     * iteration order of an internal {@link Set}, not on interface declaration order). This only matters when
     * such a conflict actually exists; a single annotated interface is resolved deterministically.
     *
     * @param resourceInfo   The instance to access resource class and method
     * @param annotationType The annotation type to get
     * @param wrapperType    The wrapper annotation type in case the annotation type is repeatable
     * @param mapper         The function to extract the annotation to get (repeatable) from its wrapper
     * @param <A>            The annotation type
     * @param <W>            The wrapper annotation type
     * @return The annotation found or {@link Optional#empty} otherwise
     */
    public static <A extends Annotation, W extends Annotation> List<A> getAnnotation(ResourceInfo resourceInfo, Class<A> annotationType, Class<W> wrapperType, Function<W, A[]> mapper) {
        Method resourceMethod = resourceInfo.getResourceMethod();
        Set<Annotation> parentAnnotations = getAnnotationsInterfaces(resourceInfo.getResourceClass(), resourceMethod);
        // Priority: Method annotations > Interfaces annotations > Class annotation
        // resourceMethod can be null for a container that does not resolve it at this stage (see
        // LoggedFilter#filter's own null-safe handling of it), hence the guards below.
        if (resourceMethod != null && resourceMethod.isAnnotationPresent(annotationType)) {
            return Arrays.asList(resourceMethod.getAnnotationsByType(annotationType));
        } else if (resourceMethod != null && wrapperType != null && resourceMethod.isAnnotationPresent(wrapperType)) {
            return Arrays.stream(resourceMethod.getAnnotationsByType(wrapperType))
                    .map(mapper)
                    .flatMap(Arrays::stream)
                    .toList();
        } else if (!parentAnnotations.isEmpty()) {
            return parentAnnotations.stream()
                    .map(instance -> wrapperType != null && wrapperType.isInstance(instance)
                            ? Arrays.asList(mapper.apply(wrapperType.cast(instance)))
                            : List.of(instance))
                    .flatMap(List::stream)
                    .filter(annotationType::isInstance)
                    .map(annotationType::cast)
                    .toList();
        } else if (resourceInfo.getResourceClass().isAnnotationPresent(annotationType)) {
            return Arrays.asList(resourceInfo.getResourceClass().getAnnotationsByType(annotationType));
        } else if (wrapperType != null && resourceInfo.getResourceClass().isAnnotationPresent(wrapperType)) {
            return Arrays.stream(resourceInfo.getResourceClass().getAnnotationsByType(wrapperType))
                    .map(mapper)
                    .flatMap(Arrays::stream)
                    .toList();
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Gets the annotations from the interfaces implemented by the given type and method.
     * <p>
     * A resource class overriding an interface method does not inherit that method's annotations (method
     * annotations are never inherited by an override, regardless of {@link java.lang.annotation.Inherited}),
     * so this is what lets such a method still pick up the annotation declared on the interface method it
     * overrides. When a matching interface method annotation is found for at least one implemented
     * interface, it takes priority over any class-level annotation declared on an implemented interface,
     * consistent with the "method &gt; interfaces &gt; class" priority documented on {@link #getAnnotation}:
     * without this, an interface's class-level annotation was returned merged together with (instead of
     * overridden by) that same interface's method-level one for the overridden method, whenever a resource
     * class implementing that interface did not redeclare the annotation on its own override.
     *
     * @param type   The type to get the annotations from
     * @param method The method to get the annotations from
     * @return The set of annotations found
     */
    public static Set<Annotation> getAnnotationsInterfaces(Class<?> type, Method method) {
        Annotation[] baseAnnotations = Optional.ofNullable(method)
                .map(AccessibleObject::getAnnotations)
                .orElse(type.getAnnotations());
        Set<Annotation> methodAnnotations = new HashSet<>(asList(baseAnnotations));
        Set<Annotation> classAnnotations = new HashSet<>();
        for (Class<?> interfaceClass : getAllInterfaces(type)) {
            for (Method interfaceMethod : interfaceClass.getMethods()) {
                if (areMethodsEqual(interfaceMethod, method)) {
                    methodAnnotations.addAll(asList(interfaceMethod.getAnnotations()));
                }
            }
            classAnnotations.addAll(asList(interfaceClass.getAnnotations()));
        }
        return methodAnnotations.isEmpty() ? classAnnotations : methodAnnotations;
    }

    /**
     * Checks if two methods are equal.
     *
     * @param method1 The first method
     * @param method2 The second method
     * @return {@code true} if the methods are equal, {@code false} otherwise
     */
    public static boolean areMethodsEqual(Method method1, Method method2) {
        return method1 != null && method2 != null
                && method1.getName().equals(method2.getName())
                && Arrays.equals(method1.getParameterTypes(), method2.getParameterTypes());
    }

}
