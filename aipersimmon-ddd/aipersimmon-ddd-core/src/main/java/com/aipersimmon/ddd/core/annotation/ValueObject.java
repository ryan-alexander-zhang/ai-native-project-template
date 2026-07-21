package com.aipersimmon.ddd.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as a value object: it has no identity, is immutable, and is compared by the equality
 * of its attributes. Prefer value objects for concepts measured or described rather than tracked.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValueObject {}
