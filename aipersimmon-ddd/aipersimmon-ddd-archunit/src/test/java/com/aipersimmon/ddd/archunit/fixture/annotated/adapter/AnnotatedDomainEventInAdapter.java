package com.aipersimmon.ddd.archunit.fixture.annotated.adapter;

import com.aipersimmon.ddd.core.annotation.DomainEvent;

/**
 * A domain event declared via {@code @DomainEvent} but placed outside the domain
 * layer. Isolated in its own fixture package so a focused test can prove the
 * domain-event containment rule catches the <em>annotation</em> path independently of
 * the marker-interface path.
 */
@DomainEvent
public class AnnotatedDomainEventInAdapter {
}
