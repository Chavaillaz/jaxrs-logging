/**
 * Propagation of the MDC context map of a thread to the tasks it hands over to other threads - executors,
 * {@link java.util.concurrent.CompletableFuture} stages and {@code @Suspended} responses - so what they log
 * carries the request they work for (see {@link com.chavaillaz.jakarta.rs.mdc.MdcPropagation}).
 */
package com.chavaillaz.jakarta.rs.mdc;
