package com.aipersimmon.ddd.cqrs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type that maintains a {@link ReadModel} from domain events: it listens
 * for the events a command records and updates the read model accordingly, in the
 * same transaction, so the query side stays consistent with the write side. The
 * concrete update methods are specific to each read model, so this is a stereotype
 * rather than a fixed interface.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Projection {
}
