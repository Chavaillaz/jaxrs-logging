package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static java.lang.reflect.Modifier.isPrivate;
import static java.lang.reflect.Modifier.isStatic;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.apache.commons.lang3.ClassUtils.getAllInterfaces;
import static org.apache.commons.lang3.ClassUtils.getAllSuperclasses;
import static org.apache.commons.lang3.reflect.TypeUtils.genericArrayType;
import static org.apache.commons.lang3.reflect.TypeUtils.getTypeArguments;
import static org.apache.commons.lang3.reflect.TypeUtils.unrollVariables;

import jakarta.ws.rs.container.ResourceInfo;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.GenericArrayType;
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

import org.apache.commons.lang3.reflect.TypeUtils;
import org.jspecify.annotations.Nullable;

/**
 * Reads the annotations configuring this library from the resource method matched by a request, the methods
 * it overrides, the interfaces and superclasses of its class and the class itself, most specific first (see
 * {@link #declarationSites}).
 * <p>
 * Stateless and uncached: the providers cache what they read through it, once per resource method.
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

        // The declaration sites come most specific first, so a mapping declared closer to the resource method
        // wins over a competing one; getAnnotationsByType finds a mapping whether it is repeated or not, the
        // compiler only wrapping two or more of them into @LoggedMappings
        Set<LoggedMapping> mergedMappings = new LinkedHashSet<>();
        for (AnnotatedElement site : declarationSites(resourceClass, resourceMethod)) {
            mergeMappings(mergedMappings, site.getAnnotationsByType(LoggedMapping.class));
        }
        return mergedMappings;
    }

    /**
     * Adds the given mappings to the merged mappings, except those competing for a parameter with a
     * mapping already merged (see {@link #areConflicting(LoggedMapping, LoggedMapping)}): one declared
     * closer to the resource method, or before it on the same site.
     *
     * @param mergedMappings The set of merged mappings
     * @param mappings       The mappings to add, in the order they are declared in
     */
    public static void mergeMappings(Set<LoggedMapping> mergedMappings, LoggedMapping... mappings) {
        for (LoggedMapping mapping : mappings) {
            if (mergedMappings.stream().noneMatch(merged -> areConflicting(mapping, merged))) {
                mergedMappings.add(mapping);
            }
        }
    }

    /**
     * Indicates whether the two given mappings compete for the same parameters, so that only one of them
     * can apply.
     * <p>
     * Header names are compared without regard to case, as HTTP defines them that way, and as they are
     * matched against the request (see {@link MappingApplier}).
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
     * Gets the given annotation from the declaration sites of the resource method matched by the current request.
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
     * Gets the given annotation from the declaration sites of the resource method matched by the current request.
     * <p>
     * The first declaration site (see {@link #declarationSites(Class, Method)}) that declares the annotation
     * type, or its repeatable wrapper, wins entirely: a more specific declaration <em>replaces</em> a less
     * specific one rather than being merged with it. This is what lets a resource method opt out of a
     * class-level configuration by redeclaring an empty one (e.g. a bare {@code @Logged}).
     *
     * @param resourceInfo   The instance to access resource class and method
     * @param annotationType The annotation type to get
     * @param wrapperType    The wrapper annotation type in case the annotation type is repeatable, {@code null} otherwise
     * @param mapper         The function to extract the annotation to get (repeatable) from its wrapper,
     *                       {@code null} if there is no wrapper type
     * @param <A>            The annotation type
     * @param <W>            The wrapper annotation type
     * @return The annotations found, or an empty list otherwise
     * @throws IllegalArgumentException if only one of the wrapper type and the mapper is given, which would
     *                                  otherwise go unnoticed until a resource declares the wrapper
     */
    public static <A extends Annotation, W extends Annotation> List<A> getAnnotation(ResourceInfo resourceInfo, Class<A> annotationType, @Nullable Class<W> wrapperType, @Nullable Function<W, A[]> mapper) {
        if ((wrapperType == null) != (mapper == null)) {
            throw new IllegalArgumentException("A wrapper type and the mapper extracting the annotations from it go together");
        }

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
     * @param wrapperType    The wrapper annotation type in case the annotation type is repeatable, {@code null} otherwise
     * @param mapper         The function to extract the annotation to get (repeatable) from its wrapper,
     *                       {@code null} if there is no wrapper type
     * @param <A>            The annotation type
     * @param <W>            The wrapper annotation type
     * @return The annotations declared on the element, or {@code null} if it declares neither the
     * annotation type nor its wrapper. An <em>empty</em> list is a meaningful result, distinct from
     * {@code null}: it means the element does declare the wrapper, but with no annotation inside it
     * (e.g. a bare {@code @Logged}), which deliberately overrides any less specific declaration.
     */
    private static <A extends Annotation, W extends Annotation> @Nullable List<A> getDeclaredAnnotation(AnnotatedElement element, Class<A> annotationType, @Nullable Class<W> wrapperType, @Nullable Function<W, A[]> mapper) {
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
     *     <li>the methods it overrides on the superclasses of the resource class, the nearest first</li>
     *     <li>the methods it implements on the interfaces of the resource class</li>
     *     <li>the interfaces implemented by the resource class</li>
     *     <li>the resource class itself</li>
     *     <li>its superclasses, the nearest first</li>
     * </ol>
     * A method annotation is never inherited by an override (regardless of
     * {@link java.lang.annotation.Inherited}), so listing the methods overridden, generic ones included -
     * their type variables resolved against the resource class - is what lets a resource method pick up an
     * annotation declared on the abstract base resource or the interface it implements. Superclasses rank
     * above interfaces there, as JAX-RS has it for the annotations it inherits.
     * <p>
     * Interfaces deliberately rank above the resource class, so an API contract declared on an interface is
     * not silently overridden by a broad annotation on the class implementing it, which the base classes it
     * extends refine rather than override.
     * <p>
     * The order within a level follows {@link org.apache.commons.lang3.ClassUtils#getAllInterfaces(Class)},
     * which is deterministic (declaration order, depth first), so a resource class implementing several
     * interfaces that each declare a competing annotation always resolves the same way.
     *
     * @param resourceClass  The resource class matched by the current request, possibly {@code null}
     * @param resourceMethod The resource method matched by the current request, possibly {@code null}
     * @return The declaration sites, in decreasing order of priority
     */
    public static List<AnnotatedElement> declarationSites(@Nullable Class<?> resourceClass, @Nullable Method resourceMethod) {
        List<AnnotatedElement> sites = new ArrayList<>();
        if (resourceMethod != null) {
            sites.add(resourceMethod);
        }
        if (resourceClass == null) {
            return sites;
        }

        List<Class<?>> superclasses = getAllSuperclasses(resourceClass).stream()
                .filter(superclass -> superclass != Object.class)
                .toList();
        List<Class<?>> interfaces = getAllInterfaces(resourceClass);
        if (resourceMethod != null) {
            sites.addAll(overriddenMethods(superclasses, Class::getDeclaredMethods, resourceClass, resourceMethod));
            sites.addAll(overriddenMethods(interfaces, Class::getMethods, resourceClass, resourceMethod));
        }

        sites.addAll(interfaces);
        sites.add(resourceClass);
        sites.addAll(superclasses);
        return sites;
    }

    /**
     * Lists the methods of the given types that the given resource method overrides or implements, in the
     * order of the types.
     *
     * @param types          The superclasses or interfaces of the resource class
     * @param methods        The methods of a type the resource method may override: those it declares for a
     *                       superclass, its members for an interface, inherited ones included
     * @param resourceClass  The resource class matched by the current request
     * @param resourceMethod The resource method matched by the current request
     * @return The methods the resource method overrides or implements
     */
    private static List<Method> overriddenMethods(List<Class<?>> types, Function<Class<?>, Method[]> methods, Class<?> resourceClass, Method resourceMethod) {
        List<Method> overridden = new ArrayList<>();
        for (Class<?> type : types) {
            for (Method method : methods.apply(type)) {
                // The resource method itself when a superclass declares it, which is already a site of its own
                if (!method.equals(resourceMethod) && isOverridable(method) && isImplementedBy(method, resourceClass, resourceMethod)) {
                    overridden.add(method);
                }
            }
        }
        return overridden;
    }

    /**
     * Indicates whether the given method can be overridden by a resource method: an instance method, which
     * is not private, nor the bridge the compiler generates for a generic signature.
     *
     * @param method The method of a superclass or an interface of the resource class
     * @return {@code true} if a resource method can override the method, {@code false} otherwise
     */
    private static boolean isOverridable(Method method) {
        int modifiers = method.getModifiers();
        return !isStatic(modifiers) && !isPrivate(modifiers) && !method.isBridge() && !method.isSynthetic();
    }

    /**
     * Indicates whether two methods have the same signature - name and parameter types.
     *
     * @param method1 The first method, possibly {@code null}
     * @param method2 The second method, possibly {@code null}
     * @return {@code true} if both methods are present and have the same signature, {@code false} otherwise
     */
    public static boolean areMethodsEqual(@Nullable Method method1, @Nullable Method method2) {
        return method1 != null && method2 != null
                && method1.getName().equals(method2.getName())
                && Arrays.equals(method1.getParameterTypes(), method2.getParameterTypes());
    }

    /**
     * Indicates whether the given method of a superclass or an interface is one the given resource method
     * overrides or implements.
     * <p>
     * It is when both have the same signature, or when they have the same once the type variables of the
     * type declaring the method are resolved against the resource class: a resource class implementing
     * {@code CrudApi<String>}, or extending {@code CrudResource<String>}, implements {@code create(T)} with a
     * {@code create(String)}, whose parameter type differs from the {@code Object} the method is erased to.
     *
     * @param method         The method of a superclass or an interface of the resource class
     * @param resourceClass  The resource class matched by the current request
     * @param resourceMethod The resource method matched by the current request
     * @return {@code true} if the resource method overrides or implements the method, {@code false} otherwise
     */
    private static boolean isImplementedBy(Method method, Class<?> resourceClass, Method resourceMethod) {
        if (areMethodsEqual(method, resourceMethod)) {
            return true;
        } else if (!method.getName().equals(resourceMethod.getName())
                || method.getParameterCount() != resourceMethod.getParameterCount()) {
            return false;
        }

        Map<TypeVariable<?>, Type> typeArguments = getTypeArguments(resourceClass, method.getDeclaringClass());
        Type[] declaredParameters = method.getGenericParameterTypes();
        Type[] resourceParameters = resourceMethod.getGenericParameterTypes();
        for (int i = 0; i < declaredParameters.length; i++) {
            if (!TypeUtils.equals(resolve(typeArguments, declaredParameters[i]),
                    resolve(typeArguments, resourceParameters[i]))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves the type variables of the given type against the given type arguments, arrays of them
     * included: {@link TypeUtils#unrollVariables(Map, Type)} leaves an {@code E[]} as it is, which no
     * {@code String[]} is then equal to, whereas it resolves an {@code E} to {@code String}.
     *
     * @param typeArguments The type arguments of the resource class, by the type variable they are given for
     * @param type          The type to resolve
     * @return The type resolved, the class of an array whose component resolves to one, or {@code null} for a
     * type variable the type arguments give nothing for
     */
    private static @Nullable Type resolve(Map<TypeVariable<?>, Type> typeArguments, Type type) {
        if (type instanceof GenericArrayType array) {
            Type component = resolve(typeArguments, array.getGenericComponentType());
            if (component instanceof Class<?> componentClass) {
                return componentClass.arrayType();
            }
            return component == null ? null : genericArrayType(component);
        }
        return unrollVariables(typeArguments, type);
    }

}
