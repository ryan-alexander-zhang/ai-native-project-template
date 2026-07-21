package com.aipersimmon.ddd.cqrs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as a read model: a shape built and stored for querying, separate from the write-side
 * aggregate. A query returns read models; they are populated by a {@link Projection} and never
 * carry domain behaviour.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadModel {}
