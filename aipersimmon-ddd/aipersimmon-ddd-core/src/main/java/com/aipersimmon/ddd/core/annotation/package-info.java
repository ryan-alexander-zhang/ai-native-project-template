/**
 * Marker annotations that tag a type with its tactical DDD role — aggregate root, entity, value
 * object, repository, domain service, identity — without requiring it to implement a framework
 * interface. They carry no behaviour; tooling and architecture tests read them to verify structure
 * and to make intent explicit. Retained at runtime so reflective tooling can see them.
 *
 * <p>This is the <em>only</em> vocabulary for those roles: it is the one that covers all of them,
 * so the {@code AggregateRoot} and {@code Entity} interfaces that shadowed two of them under the
 * same names were removed rather than kept alongside.
 *
 * <p>A domain event is the deliberate exception and is <strong>not</strong> here. It is {@link
 * com.aipersimmon.ddd.core.event.DomainEvent}, an interface, because it has to be a type: {@code
 * AbstractAggregateRoot.registerEvent} takes one as a parameter and hands back a {@code
 * List<DomainEvent>}, and an annotation cannot appear in either signature. There was an
 * {@code @DomainEvent} annotation here too; it named the same thing the interface already named,
 * and only the interface could do the work.
 */
package com.aipersimmon.ddd.core.annotation;
