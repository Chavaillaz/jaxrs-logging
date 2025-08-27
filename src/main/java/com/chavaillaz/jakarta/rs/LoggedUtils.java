package com.chavaillaz.jakarta.rs;

import static java.util.Arrays.asList;
import static org.apache.commons.lang3.ArrayUtils.containsAny;
import static org.apache.commons.lang3.ClassUtils.getAllInterfaces;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

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
     * @param resourceInfo The instance to access resource class and method
     * @param annotation   The annotation type to get
     * @param <A>          The annotation type
     * @return The annotation found or {@link Optional#empty} otherwise
     */
    public static <A extends Annotation> Optional<A> getAnnotation(ResourceInfo resourceInfo, Class<A> annotation) {
        Set<Annotation> parentAnnotations = getAnnotationsInterfaces(resourceInfo.getResourceClass(), resourceInfo.getResourceMethod());
        // Priority: Method annotations > Interfaces annotations > Class annotation
        if (resourceInfo.getResourceMethod().isAnnotationPresent(annotation)) {
            return Optional.of(resourceInfo.getResourceMethod().getAnnotation(annotation));
        } else if (!parentAnnotations.isEmpty()) {
            return parentAnnotations.stream()
                    .filter(annotation::isInstance)
                    .map(annotation::cast)
                    .findFirst();
        } else if (resourceInfo.getResourceClass().isAnnotationPresent(annotation)) {
            return Optional.of(resourceInfo.getResourceClass().getAnnotation(annotation));
        } else {
            return Optional.empty();
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
