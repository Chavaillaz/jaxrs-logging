/**
 * Propagation of the MDC context map of a thread to the tasks it hands over to other threads - executors,
 * {@link java.util.concurrent.CompletableFuture} stages and {@code @Suspended} responses - so what they log
 * carries the request they work for (see {@link com.chavaillaz.jakarta.rs.mdc.MdcPropagation}).
 * <p>
 * The package is {@link org.jspecify.annotations.NullMarked}: every type is non-null unless explicitly annotated
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package com.chavaillaz.jakarta.rs.mdc;

import org.jspecify.annotations.NullMarked;
