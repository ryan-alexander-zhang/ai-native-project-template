/**
 * Bootstrap package of the ordering service — one deployable for the ordering
 * bounded context. Its layers live under {@code com.example.ordering}
 * (domain / application / infrastructure / adapter); cross-service events are the
 * shared {@code com.example.contracts} types, carried over Kafka.
 */
package com.example.ordering;
