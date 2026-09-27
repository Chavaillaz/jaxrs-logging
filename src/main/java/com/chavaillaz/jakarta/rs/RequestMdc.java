package com.chavaillaz.jakarta.rs;

import static com.chavaillaz.jakarta.rs.LoggedField.REQUEST_BODY;
import static com.chavaillaz.jakarta.rs.LoggedField.RESPONSE_BODY;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * The MDC entries of the requests {@link LoggedFeature} logs: puts them, records which request each belongs
 * to, and removes them once that request is done, whichever thread it completes on.
 * <p>
 * MDC is thread-local, while a request can start on one thread and complete on another (a {@code @Suspended}
 * response resumed from a worker), or in the middle of another request. So every entry is recorded, value
 * included, against its request (see {@link RequestState#getMdcEntries()}), each thread keeps track of
 * the request whose entries it carries (see {@link #threadEntries}), and a thread completing a request it does
 * not carry is lent its entries meanwhile (see {@link #onBehalfOf(RequestState, Runnable)}).
 */
final class RequestMdc {

    /**
     * Entries the current thread carries for a request, the map of that request (see
     * {@link RequestState#getMdcEntries()}), so {@link #put(String, String)} records an entry against the
     * right request without being told which. It also tells the next request starting on the thread every entry
     * the previous one may have left there, names only known at runtime included.
     * <p>
     * A thread stays bound to the last request it carried until the next one starts, as a wrapper restoring the
     * context map it saved can put the entries of a request back once it completed. It holds plain strings, so
     * it cannot pin the class loader of the application, and no body once the request is done.
     */
    private static final ThreadLocal<@Nullable Map<String, String>> threadEntries = new ThreadLocal<>();

    /**
     * Fields holding a body, which a request done stops recording, see {@link #cleanup(RequestState)}.
     */
    private static final List<LoggedField> BODIES = List.of(REQUEST_BODY, RESPONSE_BODY);

    /**
     * Names of the MDC entries of the fields, without the fields left out (see
     * {@link LoggedFeatureConfiguration.Builder#withoutField(LoggedField)}).
     */
    private final Map<LoggedField, String> fieldNames;

    /**
     * Creates the MDC entries of a feature naming its fields as given.
     *
     * @param fieldNames The names of the MDC entries of the fields, without the fields left out
     */
    RequestMdc(Map<LoggedField, String> fieldNames) {
        this.fieldNames = fieldNames;
    }

    /**
     * Puts the given entry into the current thread's context map, recording it against the request the thread
     * carries, so {@link #cleanup(RequestState)} removes it once that request is done.
     *
     * @param key   The MDC key
     * @param value The value of the entry, ignored if {@code null} or blank
     */
    void put(String key, @Nullable String value) {
        // A blank value is noise in every structured line, and reads as a legitimately empty one
        if (isNotBlank(value)) {
            MDC.put(key, value);
            record(key, value);
        }
    }

    /**
     * Records the given entry as put for the request the current thread carries, see {@link #threadEntries}.
     *
     * @param key   The MDC key just put on the current thread
     * @param value The value just put for that key
     */
    private static void record(String key, String value) {
        Map<String, String> entries = threadEntries.get();
        if (entries == null) {
            // Put while no request is bound to the thread: recorded all the same, for the next request to sweep
            entries = new ConcurrentHashMap<>();
            threadEntries.set(entries);
        }
        entries.put(key, value);
    }

    /**
     * Puts the entry of the given field into the current thread's context map, unless the field is left out.
     *
     * @param field The field to put the value of
     * @param value The value of the field, ignored if {@code null} or blank
     */
    void put(LoggedField field, @Nullable String value) {
        String key = fieldNames.get(field);
        if (key != null) {
            put(key, value);
        }
    }

    /**
     * Gets the value of the given field from the current thread's context map.
     *
     * @param field The field to get the value of
     * @return The value of the field, {@code null} if it has none or is left out
     */
    @Nullable String get(LoggedField field) {
        String key = fieldNames.get(field);
        return key == null ? null : MDC.get(key);
    }

    /**
     * Gets the value put for the given field of the given request, as recorded against it rather than read
     * back from the context map, which the application may have cleared since.
     *
     * @param state The state of the request
     * @param field The field to get the value of
     * @return The value put for the field, {@code null} if none was or if the field is left out
     */
    @Nullable String getRecorded(RequestState state, LoggedField field) {
        String key = fieldNames.get(field);
        return key == null ? null : state.getMdcEntries().get(key);
    }

    /**
     * Indicates whether the given key is taken, which an entry the client chose the name of must never replace:
     * it names a field, or an entry the current thread carries already - put for the request, by the
     * application, or by another library such as a tracer.
     *
     * @param key The MDC key to check
     * @return {@code true} if the key is taken, {@code false} otherwise
     */
    boolean isTaken(String key) {
        return fieldNames.containsValue(key) || MDC.get(key) != null;
    }

    /**
     * Starts the entries of the given request on the current thread, which from then on carries them.
     * <p>
     * Sweeps first what a previous request left on the thread, the fields and its other entries (see
     * {@link #removeLeftovers()}): one completed on another thread, or whose completion the container never
     * reached, which would otherwise mislabel every line of the requests the pooled thread serves next.
     *
     * @param state The state of the request starting
     */
    void start(RequestState state) {
        removeFields();
        removeLeftovers();
        threadEntries.set(state.getMdcEntries());
    }

    /**
     * Removes from the current thread's context map what the request it carried last left there, as a request
     * no feature logs starts on it, and releases the thread from that request.
     * <p>
     * A request leaves its entries on the thread that started it when it completes elsewhere, or when the
     * runtime answers an exception no {@code ExceptionMapper} handles outside of JAX-RS, as Apache CXF does.
     */
    static void sweep() {
        if (removeLeftovers() != null) {
            threadEntries.remove();
        }
    }

    /**
     * Removes from the current thread's context map the entries the request it carried last put there, those
     * still holding the value that request put: the thread may have put its own under the same key since, as a
     * servlet filter describing the next request does.
     *
     * @return The entries of that request, {@code null} if the thread carried none
     */
    private static @Nullable Map<String, String> removeLeftovers() {
        Map<String, String> entries = threadEntries.get();
        if (entries != null) {
            entries.forEach((key, value) -> {
                if (value.equals(MDC.get(key))) {
                    MDC.remove(key);
                }
            });
        }
        return entries;
    }

    /**
     * Runs the given action on behalf of the given request, with its entries in the current thread's context
     * map, and records the entries the action puts as that request's.
     * <p>
     * A callback of a request can run on another thread than the one carrying it - a {@code @Suspended} response
     * resumed from a worker - or in the middle of another request: the thread is lent the entries of the request
     * for the action, so what it logs is labelled as that request's, and then gets its own context map back. So
     * is the thread that carried a request already completed, which a late callback would otherwise leave its
     * entries to. The thread carrying the request in progress gets back the entries the application cleared.
     *
     * @param state  The state of the request to act on behalf of
     * @param action The action to run
     */
    void onBehalfOf(RequestState state, Runnable action) {
        Map<String, String> entries = state.getMdcEntries();
        Map<String, String> previous = threadEntries.get();
        if (previous == entries && !state.isCompleted()) {
            // The thread carries the request, still in progress
            reinstate(entries);
            action.run();
            return;
        }

        Map<String, String> context = MDC.getCopyOfContextMap();
        threadEntries.set(entries);
        entries.forEach(MDC::put);
        try {
            action.run();
        } finally {
            restore(context);
            if (previous == null) {
                threadEntries.remove();
            } else {
                threadEntries.set(previous);
            }
        }
    }

    /**
     * Puts back in the current thread's context map the given entries of a request it no longer holds as they
     * were put.
     *
     * @param entries The entries recorded for the request
     */
    private static void reinstate(Map<String, String> entries) {
        entries.forEach((key, value) -> {
            if (!value.equals(MDC.get(key))) {
                MDC.put(key, value);
            }
        });
    }

    /**
     * Gives the current thread back the given context map.
     *
     * @param context The context map the thread had, {@code null} if it had none
     */
    private static void restore(@Nullable Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    /**
     * Removes from the current thread's context map every entry put for the given request, and the fields,
     * whatever put them.
     * <p>
     * The entries stay recorded against the request, for the next request starting on the thread to sweep them
     * again (see {@link #threadEntries}), but for the bodies, the one kind of entry that can be large.
     *
     * @param state The state of the request done with
     */
    void cleanup(RequestState state) {
        Map<String, String> entries = state.getMdcEntries();
        removeFields();
        entries.keySet().forEach(MDC::remove);
        for (LoggedField body : BODIES) {
            String key = fieldNames.get(body);
            if (key != null) {
                entries.remove(key);
            }
        }
    }

    /**
     * Removes the fields from the current thread's context map.
     */
    private void removeFields() {
        fieldNames.values().forEach(MDC::remove);
    }

}
