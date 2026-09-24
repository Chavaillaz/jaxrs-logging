package com.chavaillaz.jakarta.rs;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.MDC;

/**
 * The MDC entries of the requests {@link LoggedFilter} logs: puts them, records which request each of them
 * belongs to, and removes them once that request is done, whichever thread it completes on.
 * <p>
 * MDC is thread-local, while a request is not bound to a thread: it can start on one thread and complete on
 * another (a {@code @Suspended} response resumed from a worker, a reactive resource method), or complete in
 * the middle of another request, as when a request handler resumes the response of a request suspended
 * earlier. Every entry is therefore recorded against the request it belongs to (see
 * {@link LoggedRequestState#getMdcKeys()}), and each thread keeps track of the request whose entries it
 * carries (see {@link #threadKeys}), so the entries of a request are removed along with it rather than with
 * whatever else runs on the thread.
 */
final class RequestMdc {

    /**
     * Keys of the MDC entries the current thread carries for a request, bound to that request's own set
     * (see {@link LoggedRequestState#getMdcKeys()}) from the moment the request starts on this thread, so
     * that {@link #put(String, String)} records what it puts against the right request without having to be
     * told which one it is.
     * <p>
     * It is also what lets {@link #start(LoggedRequestState)} sweep every key a previous request left
     * behind, and not just the fixed {@link #fieldNames}. A request completed elsewhere - a
     * {@code @Suspended} response resumed from a worker, a reactive resource method, a container never
     * reaching the completion callbacks at all - leaves its entries on the thread that set them. Sweeping the
     * fixed fields alone was enough for {@code request-id} and its siblings, whose names are known up front,
     * but not for the keys whose names are only known at runtime: an automatic {@link LoggedMapping} derives
     * them from the parameters the client sent, and a subclass of {@link LoggedFilter} can put anything it
     * likes. Those stayed attached to the (pooled) thread and mislabelled every log line of the unrelated
     * requests it went on to serve, with no request ever overwriting them because the next client sends
     * different headers.
     * <p>
     * Bound to the thread precisely because it is the thread, not the request, that outlives the leak.
     * Removed rather than cleared once swept, so a thread pool outliving the application does not keep a
     * now-useless entry alive in each of its threads - and holding a plain set of strings, never anything of
     * this library's own, so the entries it does keep cannot pin the application's class loader.
     */
    private static final ThreadLocal<Set<String>> threadKeys = new ThreadLocal<>();

    /**
     * Names of the MDC entries of the fields {@link LoggedFilter} logs, by {@link LoggedField} name, a field
     * without a name being left out (see {@link LoggedFilter#mdcFields}).
     */
    private final Map<String, String> fieldNames;

    /**
     * Creates the MDC entries of a provider naming its fields as given.
     *
     * @param fieldNames The names of the MDC entries of the fields, by {@link LoggedField} name, read on every
     *                   use so a change made to them after this creation is taken into account
     */
    RequestMdc(Map<String, String> fieldNames) {
        this.fieldNames = fieldNames;
    }

    /**
     * Puts the given entry into the current thread's context map, recording its key against the request the
     * thread carries, so {@link #cleanup(LoggedRequestState)} removes it once that request is done.
     *
     * @param key   The MDC key
     * @param value The value to be associated with the given key, ignored if {@code null} or blank
     */
    void put(String key, String value) {
        // Blank values are dropped rather than stored as an empty entry: an always-present, always-empty
        // field (a request with no query parameter, a header sent with no value) is pure noise in every
        // structured log line of the application, and is indistinguishable from a legitimately empty one
        if (isNotBlank(value)) {
            MDC.put(key, value);
            record(key);
        }
    }

    /**
     * Records the given key as put in MDC for the request the current thread carries, see {@link #threadKeys}.
     *
     * @param key The MDC key just put on the current thread
     */
    private static void record(String key) {
        Set<String> keys = threadKeys.get();
        if (keys == null) {
            // Put while no request is bound to the thread: recorded all the same, so the next request
            // starting on this thread still sweeps it
            keys = ConcurrentHashMap.newKeySet();
            threadKeys.set(keys);
        }
        keys.add(key);
    }

    /**
     * Puts the entry of the given field into the current thread's context map, unless the field has no name
     * and is therefore left out.
     *
     * @param field The field to put the value of
     * @param value The value of the field, ignored if {@code null} or blank
     */
    void put(LoggedField field, String value) {
        String key = fieldNames.get(field.name());
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
    String get(LoggedField field) {
        String key = fieldNames.get(field.name());
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
     * it. That covers both the fixed fields, whose names are known up front, and every other key the request
     * this thread carried put on it (see {@link #threadKeys}), such as those an automatic
     * {@link LoggedMapping} derives from what the client sent.
     *
     * @param state The state of the request starting
     */
    void start(LoggedRequestState state) {
        removeFields();
        Set<String> keys = threadKeys.get();
        if (keys != null) {
            keys.forEach(MDC::remove);
        }
        threadKeys.set(state.getMdcKeys());
    }

    /**
     * Runs the given action on behalf of the given request, so the MDC entries it puts are recorded as that
     * request's (see {@link #threadKeys}) whichever thread it runs on, and are therefore removed along with
     * that request's other entries once it completes.
     * <p>
     * Needed wherever a callback can run on another thread than the one the request started on - the
     * response filter and the entity write of a {@code @Suspended} response resumed from a worker - or in
     * the middle of another request, as when a request handler resumes the response of a request suspended
     * earlier: recorded against whatever that thread carries, those entries would be removed with the wrong
     * request, or never.
     * <p>
     * The thread is handed back carrying what it did before, unless that was this very request and the
     * action completed it, in which case there is nothing left for the thread to carry.
     *
     * @param state  The state of the request to act on behalf of
     * @param action The action to run
     */
    void onBehalfOf(LoggedRequestState state, Runnable action) {
        Set<String> previous = threadKeys.get();
        threadKeys.set(state.getMdcKeys());
        try {
            action.run();
        } finally {
            if (previous == null || (previous == state.getMdcKeys() && state.isCompleted())) {
                threadKeys.remove();
            } else {
                threadKeys.set(previous);
            }
        }
    }

    /**
     * Removes from the current thread's context map every entry put for the given request (see
     * {@link LoggedRequestState#getMdcKeys()}).
     * <p>
     * Also sweeps the fixed fields as a safety net, in case a subclass of {@link LoggedFilter} still puts one
     * of those directly through {@link MDC#put(String, String)}.
     *
     * @param state The state of the request done with
     */
    void cleanup(LoggedRequestState state) {
        removeFields();
        state.getMdcKeys().forEach(MDC::remove);
        if (threadKeys.get() == state.getMdcKeys()) {
            // The request this thread carried is done: there is nothing left for a later request to sweep
            threadKeys.remove();
        }
    }

    /**
     * Removes the fixed fields from the current thread's context map, skipping those without a name, which
     * MDC would reject as a {@code null} key.
     */
    private void removeFields() {
        fieldNames.values().stream()
                .filter(Objects::nonNull)
                .forEach(MDC::remove);
    }

}
