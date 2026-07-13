package com.aipersimmon.ddd.application;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as a subscriber of an in-process domain event: it reacts to a fact
 * that happened within the bounded context — orchestrating a use case or starting a
 * process — holding no business rules of its own. A domain event is consumed within
 * its own context, so its subscriber is an application-layer concern.
 *
 * <p>This is distinct from consuming an integration event: an integration event
 * arrives from another context over a transport and is received by an inbound
 * adapter that translates it into a command, so there is no equivalent marker on the
 * integration side. Making the domain-event subscriber explicit keeps the intent
 * self-documenting and lets architecture tests locate these handlers by annotation.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainEventHandler {
}
