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
 * The MDC entries of the requests {@link LoggedFilter} logs: puts them, records which request each of them
 * belongs to, and removes them once that request is done, whichever thread it completes on.
 * <p>
 * MDC is thread-local, while a request is not bound to a thread: it can start on one thread and complete on
 * another (a {@code @Suspended} response resumed from a worker, a reactive resource method), or complete in
 * the middle of another request, as when a request handler resumes the response of a request suspended
 * earlier. Every entry is therefore recorded, value included, against the request it belongs to (see
 * {@link LoggedRequestState#getMdcEntries()}), and each thread keeps track of the request whose entries it
 * carries (see {@link #threadEntries}). The entries of a request are removed along with it rather than with
 * whatever else runs on the thread, and a thread completing a request it does not carry is lent that
 * request's entries for the time it takes (see {@link #onBehalfOf(LoggedRequestState, Runnable)}).
 */
final class RequestMdc {

    /**
     * Entries the current thread carries for a request, bound to that request's own map (see
     * {@link LoggedRequestState#getMdcEntries()}) from the moment the request starts on this thread, so that
     * {@link #put(String, String)} records what it puts against the right request without having to be told
     * which one it is.
     * <p>
     * It is also what lets {@link #start(LoggedRequestState)} sweep every entry a previous request left
     * behind, and not just the fixed {@link #fieldNames}. A request completed elsewhere, or never completed
     * at all, leaves its entries on the thread that set them, including those whose names are only known at
     * runtime: an automatic {@link LoggedMapping} derives them from the parameters the client sent, and a
     * subclass of {@link LoggedFilter} can put anything it likes. Left there, they would mislabel every log
     * line of the unrelated requests the (pooled) thread goes on to serve.
     * <p>
     * A thread stays bound to the last request it carried until the next one starts, even once that request is
     * done: its entries can come back after it completed, as a wrapper restoring the context map it saved before
     * the completion puts them back - {@code MdcPropagation} around a task resuming a response on the thread
     * that carried its request, for instance. What stays bound holds plain strings, never anything of this
     * library's own, so it cannot pin the application's class loader, and no longer holds the bodies once the
     * request is done (see {@link #cleanup(LoggedRequestState)}).
     */
    private static final ThreadLocal<@Nullable Map<String, String>> threadEntries = new ThreadLocal<>();

    /**
     * Fields holding a body, which a request done stops recording, see {@link #cleanup(LoggedRequestState)}.
     */
    private static final List<LoggedField> BODIES = List.of(REQUEST_BODY, RESPONSE_BODY);

    /**
     * Names of the MDC entries of the fields {@link LoggedFilter} logs, a field without a name being left out
     * (see {@link LoggedFilterConfiguration.Builder#withoutField(LoggedField)}).
     */
    private final Map<LoggedField, String> fieldNames;

    /**
     * Creates the MDC entries of a provider naming its fields as given.
     *
     * @param fieldNames The names of the MDC entries of the fields, without the fields left out
     */
    RequestMdc(Map<LoggedField, String> fieldNames) {
        this.fieldNames = fieldNames;
    }

    /**
     * Puts the given entry into the current thread's context map, recording it against the request the thread
     * carries, so {@link #cleanup(LoggedRequestState)} removes it once that request is done.
     *
     * @param key   The MDC key
     * @param value The value to be associated with the given key, ignored if {@code null} or blank
     */
    void put(String key, @Nullable String value) {
        // Blank values are dropped rather than stored as an empty entry: an always-present, always-empty
        // field (a request with no query parameter, a header sent with no value) is pure noise in every
        // structured log line of the application, and is indistinguishable from a legitimately empty one
        if (isNotBlank(value)) {
            MDC.put(key, value);
            record(key, value);
        }
    }

    /**
     * Records the given entry as put in MDC for the request the current thread carries, see
     * {@link #threadEntries}.
     *
     * @param key   The MDC key just put on the current thread
     * @param value The value just put for that key
     */
    private static void record(String key, String value) {
        Map<String, String> entries = threadEntries.get();
        if (entries == null) {
            // Put while no request is bound to the thread: recorded all the same, so the next request
            // starting on this thread still sweeps it
            entries = new ConcurrentHashMap<>();
            threadEntries.set(entries);
        }
        entries.put(key, value);
    }

    /**
     * Puts the entry of the given field into the current thread's context map, unless the field has no name
     * and is therefore left out.
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
     * @return The value of the field, {@code null} if it has none or no name
     */
    @Nullable String get(LoggedField field) {
        String key = fieldNames.get(field);
        return key == null ? null : MDC.get(key);
    }

    /**
     * Indicates whether the given key is the name of one of the fields, which an entry the client chose the
     * name of must never overwrite.
     *
     * @param key The MDC key to check
     * @return {@code true} if the key names a field, {@code false} otherwise
     */
    boolean isField(String key) {
        return fieldNames.containsValue(key);
    }

    /**
     * Starts the entries of the given request on the current thread, which from then on carries them.
     * <p>
     * Sweeps first whatever a previous request left on the thread, as a safety net rather than the normal
     * cleanup path (which is {@link #cleanup(LoggedRequestState)}): MDC is thread-local and request threads
     * are pooled, so an entry that could not be removed at the end of a previous request - because it
     * completed on another thread, or because the container never reached the completion callbacks (an
     * entity whose {@code MessageBodyWriter} failed to be selected, for instance) - would otherwise stay
     * attached to this thread and silently mislabel every log line of the unrelated request now running on
     * it. That covers both the fixed fields, whose names are known up front, and every other entry the
     * request this thread carried put on it (see {@link #threadEntries}).
     *
     * @param state The state of the request starting
     */
    void start(LoggedRequestState state) {
        removeFields();
        Map<String, String> entries = threadEntries.get();
        if (entries != null) {
            entries.keySet().forEach(MDC::remove);
        }
        threadEntries.set(state.getMdcEntries());
    }

    /**
     * Runs the given action on behalf of the given request, with the entries of that request in the current
     * thread's context map, and the entries the action puts recorded as that request's, so they are removed
     * along with its other entries once it completes.
     * <p>
     * Needed wherever a callback can run on another thread than the one carrying the request - the response
     * filter and the entity write of a {@code @Suspended} response resumed from a worker - or in the middle of
     * another request, as when a request handler resumes the response of a request suspended earlier. Such a
     * thread is lent the entries of the request for the duration of the action, so what it logs is labelled
     * as that request's rather than with nothing or, worse, as the request the thread is serving, which gets
     * its own context back afterwards, untouched by the completion of the other one.
     * <p>
     * The thread that carried a request already completed is lent its entries all the same, as a later
     * callback of that request would otherwise leave on it whatever it puts. A thread lent the entries is
     * handed back carrying what it did before, context map included.
     *
     * @param state  The state of the request to act on behalf of
     * @param action The action to run
     */
    void onBehalfOf(LoggedRequestState state, Runnable action) {
        Map<String, String> entries = state.getMdcEntries();
        Map<String, String> previous = threadEntries.get();
        if (previous == entries && !state.isCompleted()) {
            // The thread carries the request, still in progress
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
     * Removes from the current thread's context map every entry put for the given request (see
     * {@link LoggedRequestState#getMdcEntries()}).
     * <p>
     * Also sweeps the fixed fields as a safety net, in case a subclass of {@link LoggedFilter} still puts one
     * of those directly through {@link MDC#put(String, String)}.
     * <p>
     * The entries stay recorded against the request, for the thread that carried it to sweep them again once
     * the next request starts on it (see {@link #threadEntries}), but for the bodies: put as the request
     * completes, after anything could have saved them to put them back, they are the one kind of entry that
     * can be large.
     *
     * @param state The state of the request done with
     */
    void cleanup(LoggedRequestState state) {
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
     * Removes the fixed fields from the current thread's context map.
     */
    private void removeFields() {
        fieldNames.values().forEach(MDC::remove);
    }

}
