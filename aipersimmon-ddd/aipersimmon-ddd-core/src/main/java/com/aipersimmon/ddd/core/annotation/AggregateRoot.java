package com.aipersimmon.ddd.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as an aggregate root: the single entry point to an aggregate and the boundary of one
 * transactional consistency unit. Other aggregates reference it only by identity, never by holding
 * the root instance.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AggregateRoot {}
