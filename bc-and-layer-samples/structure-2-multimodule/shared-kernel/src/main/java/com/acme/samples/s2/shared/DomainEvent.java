package com.acme.samples.s2.shared;

/**
 * Marker for an in-process domain event — a fact in a bounded context's
 * ubiquitous language (analysis-00002). It is an internal implementation detail,
 * NOT the cross-context contract; that is a separate, thin integration event in a
 * context's {@code *-api}.
 */
public interface DomainEvent {
}
