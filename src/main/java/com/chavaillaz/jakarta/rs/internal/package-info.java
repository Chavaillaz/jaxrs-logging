/**
 * Internals shared by the server and client sides of this library: the capture of bodies, the instantiation of
 * body filters, the body logging configuration, the names of credentials, the sanitizing of the values logged
 * and the guard around logging.
 * <p>
 * Not part of the API: the module does not export this package, and nothing here is kept compatible from a
 * version to the next.
 * <p>
 * The package is {@link org.jspecify.annotations.NullMarked}: every type is non-null unless explicitly annotated
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package com.chavaillaz.jakarta.rs.internal;

import org.jspecify.annotations.NullMarked;
