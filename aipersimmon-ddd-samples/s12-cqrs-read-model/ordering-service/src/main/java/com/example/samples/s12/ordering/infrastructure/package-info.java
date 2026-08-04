/**
 * Persistence: the write model, this context's replica of the catalogue's names, and the projection.
 *
 * <p>Three tables with three different lifecycles, and the adapters keep them apart. Nothing here decides what
 * a projection row contains — that lives in the application layer, so it can be tested without a database and
 * so there is exactly one definition of it.
 */
package com.example.samples.s12.ordering.infrastructure;
