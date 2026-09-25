package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.apache.commons.lang3.ClassUtils.getAllInterfaces;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

import jakarta.ws.rs.container.ResourceInfo;
import org.apache.commons.lang3.reflect.TypeUtils;

/**
 * Reads the annotations configuring this library from the resource method matched by a request, the
 * interfaces of its class and the class itself, most specific first (see {@link #declarationSites}).
 * <p>
 * Stateless and uncached: {@link LoggedResolver} caches what is read here, once per resource method.
 */
public final class LoggedUtils {

    private LoggedUtils() {
        // Utility class
    }

    /**
     * Merges the {@link LoggedMapping} annotations declared on every declaration site of the matched resource
     * method (see {@link #declarationSites}), a mapping competing for a parameter with one declared on a more
     * specific site being left out (see {@link #mergeMappings}).
     *
     * @param resourceInfo The instance to access resource class and method
     * @return The merged mappings, in the order of their declaration sites
     */
    public static Set<LoggedMapping> getMergedMappings(ResourceInfo resourceInfo) {
        Class<?> resourceClass = resourceInfo.getResourceClass();
        Method resourceMethod = resourceInfo.getResourceMethod();
        if (resourceClass == null && resourceMethod == null) {
            return emptySet();
        }

        // Priority: Method annotations > Interfaces annotations > Class annotation
        // Uses getAnnotationsByType() throughout, as it is the only lookup that correctly finds a
        // @LoggedMapping regardless of whether it is declared once or repeated (java.lang.annotation.Repeatable
        // only synthesizes the @LoggedMappings container when 2+ instances are present, so a single
        // annotation would be missed by a plain getAnnotation(LoggedMappings.class) lookup).
        // Iterates the declaration sites in priority order (see declarationSites), which is deterministic,
        // so a mapping declared closer to the resource method always wins over a competing one.
        Set<LoggedMapping> mergedMappings = new LinkedHashSet<>();
        for (AnnotatedElement site : declarationSites(resourceClass, resourceMethod)) {
            mergeMappings(mergedMappings, site.getAnnotationsByType(LoggedMapping.class));
        }
        return mergedMappings;
    }

    /**
     * Adds the given mappings to the merged mappings, except those competing for a parameter with a
     * mapping already merged (see {@link #areConflicting(LoggedMapping, LoggedMapping)}), which was
     * declared closer to the resource method.
     *
     * @param mergedMappings The set of merged mappings
     * @param mappings       The mappings to add
     */
    public static void mergeMappings(Set<LoggedMapping> mergedMappings, LoggedMapping... mappings) {
        stream(mappings)
                .filter(newMapping -> mergedMappings.stream()
                        .noneMatch(existingMapping -> areConflicting(newMapping, existingMapping)))
                .forEach(mergedMappings::add);
    }

    /**
     * Indicates whether the two given mappings compete for the same parameters, so that only one of them
     * can apply.
     * <p>
     * Header names are compared without regard to case, as HTTP defines them that way, and as they are
     * matched against the request (see {@link MappingApplier}). Compared as written, a header a method maps
     * as {@code User-Agent} and its class as {@code user-agent} was kept twice, and the mapping applied was
     * then whichever sorted first rather than the one declared closest to the resource method.
     *
     * @param first  The first mapping
     * @param second The second mapping
     * @return {@code true} if the mappings compete for a parameter, {@code false} otherwise
     */
    private static boolean areConflicting(LoggedMapping first, LoggedMapping second) {
        if (first.type() != second.type()) {
            return false;
        } else if (first.auto() && second.auto()) {
            // Two automatic mappings of the same type would otherwise both apply, as neither declares
            // any parameter name to detect the conflict with
            return true;
        }
        BiPredicate<String, String> sameName = first.type() == HEADER ? String::equalsIgnoreCase : String::equals;
        return stream(first.paramNames())
                .anyMatch(name -> stream(second.paramNames()).anyMatch(other -> sameName.test(name, other)));
    }

    /**
     * Gets the given annotation from the resource method, its interfaces or its class matched by the current request.
     *
     * @param resourceInfo   The instance to access resource class and method
     * @param annotationType The annotation type to get
     * @param <A>            The annotation type
     * @return The annotations found, or an empty list otherwise
     */
    public static <A extends Annotation> List<A> getAnnotation(ResourceInfo resourceInfo, Class<A> annotationType) {
        return getAnnotation(resourceInfo, annotationType, null, null);
    }

    /**
     * Gets the given annotation from the resource method, its interfaces or its class matched by the current request.
     * <p>
     * The first declaration site (see {@link #declarationSites(Class, Method)}) that declares the annotation
     * type, or its repeatable wrapper, wins entirely: a more specific declaration <em>replaces</em> a less
     * specific one rather than being merged with it. This is what lets a resource method opt out of a
     * class-level configuration by redeclaring an empty one (e.g. a bare {@code @Logged}).
     *
     * @param resourceInfo   The instance to access resource class and method
     * @param annotationType The annotation type to get
     * @param wrapperType    The wrapper annotation type in case the annotation type is repeatable
     * @param mapper         The function to extract the annotation to get (repeatable) from its wrapper
     * @param <A>            The annotation type
     * @param <W>            The wrapper annotation type
     * @return The annotations found, or an empty list otherwise
     */
    public static <A extends Annotation, W extends Annotation> List<A> getAnnotation(ResourceInfo resourceInfo, Class<A> annotationType, Class<W> wrapperType, Function<W, A[]> mapper) {
        Class<?> resourceClass = resourceInfo.getResourceClass();
        Method resourceMethod = resourceInfo.getResourceMethod();
        if (resourceClass == null && resourceMethod == null) {
            return emptyList();
        }

        for (AnnotatedElement site : declarationSites(resourceClass, resourceMethod)) {
            List<A> declared = getDeclaredAnnotation(site, annotationType, wrapperType, mapper);
            if (declared != null) {
                return declared;
            }
        }
        return emptyList();
    }

    /**
     * Gets the annotation, or the content of its repeatable wrapper, as declared on the given element.
     *
     * @param element        The element to read the annotation from
     * @param annotationType The annotation type to get
     * @param wrapperType    The wrapper annotation type in case the annotation type is repeatable
     * @param mapper         The function to extract the annotation to get (repeatable) from its wrapper
     * @param <A>            The annotation type
     * @param <W>            The wrapper annotation type
     * @return The annotations declared on the element, or {@code null} if it declares neither the
     * annotation type nor its wrapper. An <em>empty</em> list is a meaningful result, distinct from
     * {@code null}: it means the element does declare the wrapper, but with no annotation inside it
     * (e.g. a bare {@code @Logged}), which deliberately overrides any less specific declaration.
     */
    private static <A extends Annotation, W extends Annotation> List<A> getDeclaredAnnotation(AnnotatedElement element, Class<A> annotationType, Class<W> wrapperType, Function<W, A[]> mapper) {
        if (element.isAnnotationPresent(annotationType)) {
            return asList(element.getAnnotationsByType(annotationType));
        } else if (wrapperType != null && element.isAnnotationPresent(wrapperType)) {
            return stream(element.getAnnotationsByType(wrapperType))
                    .map(mapper)
                    .flatMap(Arrays::stream)
                    .toList();
        }
        return null;
    }

    /**
     * Lists the places an annotation can be declared for a given resource method, from the most specific
     * to the least specific:
     * <ol>
     *     <li>the resource method itself</li>
     *     <li>the methods it overrides on the interfaces implemented by the resource class, generic ones
     *     included, their type variables being resolved against the resource class - a method annotation is
     *     never inherited by an override (regardless of {@link java.lang.annotation.Inherited}), so this is
     *     what lets an implementation pick up an annotation declared on the interface it implements</li>
     *     <li>the interfaces implemented by the resource class</li>
     *     <li>the resource class itself</li>
     * </ol>
     * Interfaces deliberately rank above the resource class, keeping the "method &gt; interfaces &gt; class"
     * priority this library has always documented: an API contract declared on an interface is not silently
     * overridden by a broad annotation on the class implementing it.
     * <p>
     * The order within a level follows {@link org.apache.commons.lang3.ClassUtils#getAllInterfaces(Class)},
     * which is deterministic (declaration order, depth first), so a resource class implementing several
     * interfaces that each declare a competing annotation always resolves the same way, rather than
     * depending on the iteration order of a hash-based collection as it used to.
     *
     * @param resourceClass  The resource class matched by the current request, possibly {@code null}
     * @param resourceMethod The resource method matched by the current request, possibly {@code null}
     * @return The declaration sites, in decreasing order of priority
     */
    public static List<AnnotatedElement> declarationSites(Class<?> resourceClass, Method resourceMethod) {
        List<AnnotatedElement> sites = new ArrayList<>();
        if (resourceMethod != null) {
            sites.add(resourceMethod);
        }

        List<Class<?>> interfaces = resourceClass == null ? List.of() : getAllInterfaces(resourceClass);
        for (Class<?> interfaceClass : interfaces) {
            for (Method interfaceMethod : interfaceClass.getMethods()) {
                if (isImplementedBy(interfaceMethod, resourceClass, resourceMethod)) {
                    sites.add(interfaceMethod);
                }
            }
        }

        sites.addAll(interfaces);
        if (resourceClass != null) {
            sites.add(resourceClass);
        }
        return sites;
    }

    /**
     * Indicates whether two methods have the same signature - name and parameter types.
     *
     * @param method1 The first method
     * @param method2 The second method
     * @return {@code true} if both methods are present and have the same signature, {@code false} otherwise
     */
    public static boolean areMethodsEqual(Method method1, Method method2) {
        return method1 != null && method2 != null
                && method1.getName().equals(method2.getName())
                && Arrays.equals(method1.getParameterTypes(), method2.getParameterTypes());
    }

    /**
     * Indicates whether the given interface method is one the given resource method implements.
     * <p>
     * It is when both have the same signature, or when they have the same once the type variables of the
     * interface are resolved against the resource class: a resource class implementing {@code CrudApi<String>}
     * implements {@code create(T)} with a {@code create(String)}, whose parameter type differs from the
     * {@code Object} the interface method is erased to.
     *
     * @param interfaceMethod The method of an interface of the resource class
     * @param resourceClass   The resource class matched by the current request
     * @param resourceMethod  The resource method matched by the current request, possibly {@code null}
     * @return {@code true} if the resource method implements the interface method, {@code false} otherwise
     */
    private static boolean isImplementedBy(Method interfaceMethod, Class<?> resourceClass, Method resourceMethod) {
        if (areMethodsEqual(interfaceMethod, resourceMethod)) {
            return true;
        } else if (resourceMethod == null
                || !interfaceMethod.getName().equals(resourceMethod.getName())
                || interfaceMethod.getParameterCount() != resourceMethod.getParameterCount()) {
            return false;
        }

        Map<TypeVariable<?>, Type> typeArguments = TypeUtils.getTypeArguments(resourceClass, interfaceMethod.getDeclaringClass());
        Type[] interfaceParameters = interfaceMethod.getGenericParameterTypes();
        Type[] resourceParameters = resourceMethod.getGenericParameterTypes();
        for (int i = 0; i < interfaceParameters.length; i++) {
            if (!TypeUtils.equals(TypeUtils.unrollVariables(typeArguments, interfaceParameters[i]),
                    TypeUtils.unrollVariables(typeArguments, resourceParameters[i]))) {
                return false;
            }
        }
        return true;
    }

}
