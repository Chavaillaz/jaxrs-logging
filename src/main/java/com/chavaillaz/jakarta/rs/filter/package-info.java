/**
 * Filters rewriting a captured body before it is logged (see
 * {@link com.chavaillaz.jakarta.rs.filter.LoggedBodyFilter}), and the ready-made ones masking what must not
 * reach the logs: the value of JSON properties, of form parameters, or of whatever a regular expression
 * captures.
 */
package com.chavaillaz.jakarta.rs.filter;
