package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.cqrs.Command;

/**
 * Violates {@code commandsAndQueriesShouldResideInApplication}: a command declared in the domain
 * layer, which makes the model aware of the use cases that drive it.
 *
 * <p>Also violates {@code domainShouldDependOnTheFrameworkCoreOnly} — a domain type cannot name
 * {@code Command} without depending on the CQRS module — which is the shape of the mistake rather
 * than an accident of the fixture: the two rules catch the same drift from opposite ends.
 */
public record BadCommandInDomain(String orderId) implements Command<Void> {}
