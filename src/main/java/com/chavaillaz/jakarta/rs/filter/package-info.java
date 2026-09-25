/**
 * Filters rewriting a captured body before it is logged (see
 * {@link com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter}), and the ready-made ones masking what must not
 * reach the logs: the value of JSON properties, of form parameters, or of whatever a regular expression
 * captures.
 * <p>
 * The package is {@link org.jspecify.annotations.NullMarked}: every type is non-null unless explicitly annotated
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package com.chavaillaz.jakarta.rs.filter;

import org.jspecify.annotations.NullMarked;
