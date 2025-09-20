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

        // Priority: Method annotations > Interfaces annotations > Class annotation
        Optional.ofNullable(resourceInfo.getResourceMethod().getAnnotation(LoggedMappings.class))
                .ifPresent(mappings -> mergeMappings(mergedMappings, mappings.value()));
        getAnnotationsInterfaces(resourceInfo.getResourceClass(), resourceInfo.getResourceMethod()).stream()
                .filter(LoggedMapping.class::isInstance)
                .map(LoggedMapping.class::cast)
                .forEach(mappings -> mergeMappings(mergedMappings, mappings));
        Optional.ofNullable(resourceInfo.getResourceClass().getAnnotation(LoggedMappings.class))
                .ifPresent(mappings -> mergeMappings(mergedMappings, mappings.value()));

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
        Set<Annotation> parentAnnotations = getAnnotationsInterfaces(resourceInfo.getResourceClass(), resourceInfo.getResourceMethod());
        // Priority: Method annotations > Interfaces annotations > Class annotation
        if (resourceInfo.getResourceMethod().isAnnotationPresent(annotationType)) {
            return Arrays.asList(resourceInfo.getResourceMethod().getAnnotationsByType(annotationType));
        } else if (wrapperType != null && resourceInfo.getResourceMethod().isAnnotationPresent(wrapperType)) {
            return Arrays.stream(resourceInfo.getResourceMethod().getAnnotationsByType(wrapperType))
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
     *
     * @param type   The type to get the annotations from
     * @param method The method to get the annotations from
     * @return The set of annotations found
     */
    public static Set<Annotation> getAnnotationsInterfaces(Class<?> type, Method method) {
        Annotation[] baseAnnotations = Optional.ofNullable(method)
                .map(AccessibleObject::getAnnotations)
                .orElse(type.getAnnotations());
        Set<Annotation> annotations = new HashSet<>(asList(baseAnnotations));
        for (Class<?> interfaceClass : getAllInterfaces(type)) {
            for (Method interfaceMethod : interfaceClass.getMethods()) {
                if (areMethodsEqual(interfaceMethod, method)) {
                    annotations.addAll(asList(interfaceMethod.getAnnotations()));
                }
            }
            annotations.addAll(asList(interfaceClass.getAnnotations()));
        }
        return annotations;
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
