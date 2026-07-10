/**
 * Bootstrap package of the inventory service — one deployable for the inventory
 * bounded context. Its layers live under {@code com.example.inventory}
 * (domain / application / infrastructure / adapter); cross-service events are the
 * shared {@code com.example.contracts} types, carried over Kafka.
 */
package com.example.inventory;
