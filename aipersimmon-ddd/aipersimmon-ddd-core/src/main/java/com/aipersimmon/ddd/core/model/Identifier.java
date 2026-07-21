package com.aipersimmon.ddd.core.model;

/**
 * Marker for a type that serves as the identity of an entity or aggregate root. Identifiers are
 * value objects: immutable and compared by value. Modelling them as dedicated types, rather than
 * raw {@code String} or {@code UUID}, keeps the identities of different aggregates from being mixed
 * up.
 */
public interface Identifier {}
