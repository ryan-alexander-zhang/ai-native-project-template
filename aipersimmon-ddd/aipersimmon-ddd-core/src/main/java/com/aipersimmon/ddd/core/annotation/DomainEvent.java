package com.aipersimmon.ddd.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as an in-process domain event: a fact that occurred inside a
 * bounded context, expressed in its ubiquitous language. It is an internal
 * detail of the context, not the versioned contract published to other contexts.
 *
 * <p>Use this on a type that does not implement the domain-event marker interface;
 * both express the same role.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainEvent {
}
