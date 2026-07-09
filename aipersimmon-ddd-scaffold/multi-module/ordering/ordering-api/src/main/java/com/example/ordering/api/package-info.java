/**
 * The ordering context's published contract: thin integration events that other
 * bounded contexts may consume. These are a versioned, backward-compatible
 * language, distinct from the context's internal domain events; they carry ids
 * and minimal data, never internal domain types.
 */
package com.example.ordering.api;
