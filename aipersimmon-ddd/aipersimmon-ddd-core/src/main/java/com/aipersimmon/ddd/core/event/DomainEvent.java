package com.aipersimmon.ddd.core.event;

/**
 * Marker for an in-process domain event: a fact that occurred inside a bounded context, part of its
 * ubiquitous language. It is an internal implementation detail, not the cross-context integration
 * contract published to other contexts.
 */
public interface DomainEvent {}
