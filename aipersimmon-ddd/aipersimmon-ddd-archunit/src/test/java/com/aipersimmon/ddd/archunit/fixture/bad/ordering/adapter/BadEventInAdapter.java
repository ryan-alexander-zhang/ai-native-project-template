package com.aipersimmon.ddd.archunit.fixture.bad.ordering.adapter;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** Violates domain-event containment: a domain event in the interface layer. */
public class BadEventInAdapter implements DomainEvent {}
