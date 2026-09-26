package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedMapping.MappingType.HEADER;
import static com.chavaillaz.jakarta.rs.internal.Sanitizer.sanitize;
import static java.lang.String.CASE_INSENSITIVE_ORDER;
import static java.util.Comparator.comparing;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import com.chavaillaz.jakarta.rs.LoggedMapping.MappingType;

/**
 * Works out the MDC entries the {@link LoggedMapping} annotations of a resource method ask for, from the
 * parameters of a request.
 * <p>
 * Kept apart from any request context: it reads the parameters it is handed and hands every entry over to
 * the given output, so its rules can be exercised on plain maps, and the order the mappings apply in can be
 * worked out once per resource method (see {@link #inApplicationOrder(Collection)}) rather than on every
 * request.
 * <p>
 * Mappings apply in an order chosen so that the more specific intent wins: exclusions first (a mapping
 * declaring no MDC key means "never map this"), then explicit mappings, then automatic ones. Each explicit
 * mapping claims the names of the parameters it reads, per type of parameter, and a later mapping leaves a
 * claimed parameter alone, so no parameter is mapped twice under two different keys, nor at all once
 * excluded. An automatic mapping only ever adds entries, and never replaces one (see
 * {@link #applyAutomatic}).
 */
final class MappingApplier {

    /**
     * Order the mappings apply in: explicit ones before automatic ones, so an automatic mapping only maps
     * what no explicit one claimed, and on their MDC key within each, which puts exclusions - declaring
     * none - first. The sort being stable, mappings equal on both keep the order they were declared in.
     */
    private static final Comparator<LoggedMapping> APPLICATION_ORDER = comparing(LoggedMapping::auto)
            .thenComparing(LoggedMapping::mdcKey);

    private final BiPredicate<MappingType, String> sensitive;
    private final Predicate<String> taken;

    /**
     * Creates an applier guarding automatic mappings with the given predicates.
     *
     * @param sensitive Whether the value of the parameter of the given type and name must be kept out of the logs
     * @param taken     Whether the given MDC key is taken already, by one of the provider's own fields or by an
     *                  entry the request carries
     */
    MappingApplier(BiPredicate<MappingType, String> sensitive, Predicate<String> taken) {
        this.sensitive = sensitive;
        this.taken = taken;
    }

    /**
     * Sorts the given mappings in the order they apply in.
     *
     * @param mappings The mappings of a resource method, in the order they were declared in
     * @return The same mappings, in the order they apply in
     */
    static List<LoggedMapping> inApplicationOrder(Collection<LoggedMapping> mappings) {
        return mappings.stream()
                .sorted(APPLICATION_ORDER)
                .toList();
    }

    /**
     * Applies the given mappings to the parameters of a request.
     *
     * @param mappings   The mappings to apply, in the order they apply in (see {@link #inApplicationOrder(Collection)})
     * @param parameters The parameters of the request of the given type, by name
     * @param output     What to do with each MDC entry the mappings ask for, given its key and its value
     */
    void apply(List<LoggedMapping> mappings, Function<MappingType, Map<String, List<String>>> parameters, BiConsumer<String, @Nullable String> output) {
        if (mappings.isEmpty()) {
            // The case of most resources, on the path of every request they serve
            return;
        }

        Map<MappingType, Set<String>> claimed = new EnumMap<>(MappingType.class);
        // The keys of the explicit mappings, which apply first: whether the request carries the parameters
        // they read or not, an automatic mapping never puts an entry under one of them
        Set<String> explicitKeys = new HashSet<>();
        for (LoggedMapping mapping : mappings) {
            Set<String> claimedNames = claimed.computeIfAbsent(mapping.type(), MappingApplier::newClaimedNames);
            if (mapping.auto()) {
                applyAutomatic(mapping, parameters.apply(mapping.type()), claimedNames, explicitKeys, output);
            } else if (claim(mapping, claimedNames) && isNotBlank(mapping.mdcKey())) {
                String key = mapping.mdcPrefix() + mapping.mdcKey();
                explicitKeys.add(key);
                applyExplicit(mapping, key, parameters.apply(mapping.type()), output);
            }
        }
    }

    /**
     * Creates the set recording the names of the parameters of the given type claimed by the mappings.
     * <p>
     * Header names are compared without regard to case, as HTTP defines them that way and nothing makes
     * a client spell one the way the annotation naming it does - HTTP/2 and HTTP/3 send every header
     * name lower cased, whatever the application wrote - so a header a mapping names, or excludes, is never
     * mapped again by an automatic mapping. Path and query parameter names are case-sensitive and are matched
     * as written.
     *
     * @param type The type of parameter the mappings read
     * @return The (empty) set to record claimed names in
     */
    private static Set<String> newClaimedNames(MappingType type) {
        return type == HEADER ? new TreeSet<>(CASE_INSENSITIVE_ORDER) : new HashSet<>();
    }

    /**
     * Claims the names of the parameters the given explicit mapping reads, unless a mapping applied before
     * it already claimed one of them, in which case the given mapping does nothing at all.
     * <p>
     * An exclusion - a mapping declaring no MDC key - only claims its names, so that no later mapping (in
     * particular an automatic one, which applies last for exactly this reason) can map them.
     *
     * @param mapping      The explicit mapping to claim the parameters of
     * @param claimedNames The names of the parameters of its type claimed so far
     * @return {@code true} if the names were claimed, {@code false} if the mapping must be left out
     */
    private static boolean claim(LoggedMapping mapping, Set<String> claimedNames) {
        String[] names = mapping.paramNames();
        for (String name : names) {
            if (claimedNames.contains(name)) {
                return false;
            }
        }
        claimedNames.addAll(List.of(names));
        return true;
    }

    /**
     * Maps the first of the parameters named by the given mapping that the request has to the MDC key the
     * mapping declares.
     * <p>
     * The names are read in the order they are declared, so "the first of the parameters" is the same one
     * from a restart of the application to the next whenever several of them are present, and a name
     * declared twice is merely read twice rather than being an error.
     *
     * @param mapping    The explicit mapping to apply
     * @param key        The MDC key the mapping declares, its prefix included
     * @param parameters The parameters of the request of the type of the mapping, by name
     * @param output     What to do with the MDC entry the mapping asks for
     */
    private static void applyExplicit(LoggedMapping mapping, String key, Map<String, List<String>> parameters, BiConsumer<String, @Nullable String> output) {
        for (String name : mapping.paramNames()) {
            List<String> values = parameters.get(name);
            if (values != null && !values.isEmpty()) {
                output.accept(key, sanitize(values.getFirst()));
                return;
            }
        }
    }

    /**
     * Maps every parameter the client sent and no explicit mapping claimed to an MDC entry named after it,
     * for a mapping declared with {@link LoggedMapping#auto()}.
     * <p>
     * The MDC key is therefore chosen by the caller, not by the application, which is what the guards here
     * are about: a parameter carrying a credential is skipped outright rather than logged, and an entry is
     * only ever added, never put under a key already taken - one of the provider's own fields, the key of an
     * explicit mapping, or an entry the request carries, put by the application or by another library - so
     * no client can relabel its request by naming a parameter {@code request-id}, or the {@code tenant} an
     * explicit mapping reads from a header the gateway sets.
     *
     * @param mapping      The automatic mapping to apply
     * @param parameters   The parameters of the request of the type of the mapping, by name
     * @param claimedNames The names of the parameters of that type claimed by explicit mappings
     * @param explicitKeys The MDC keys of the explicit mappings
     * @param output       What to do with each MDC entry the mapping asks for
     */
    private void applyAutomatic(LoggedMapping mapping, Map<String, List<String>> parameters, Set<String> claimedNames, Set<String> explicitKeys, BiConsumer<String, @Nullable String> output) {
        for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
            String name = parameter.getKey();
            List<String> values = parameter.getValue();
            if (claimedNames.contains(name) || sensitive.test(mapping.type(), name) || values == null || values.isEmpty()) {
                continue;
            }
            String sanitizedName = sanitize(name);
            if (isBlank(sanitizedName)) {
                // No key to map it under: "?=value" has no name, and a log shipper rejects a line carrying an
                // entry without one, as Elasticsearch does a field without a name
                continue;
            }
            String key = mapping.mdcPrefix() + sanitizedName;
            if (!explicitKeys.contains(key) && !taken.test(key)) {
                output.accept(key, sanitize(values.getFirst()));
            }
        }
    }

}
