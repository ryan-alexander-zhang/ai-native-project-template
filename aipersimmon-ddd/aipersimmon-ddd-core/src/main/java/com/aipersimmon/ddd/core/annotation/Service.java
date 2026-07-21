package com.aipersimmon.ddd.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as a domain service: stateless domain behaviour that does not naturally belong to a
 * single entity or value object. It operates on domain objects and holds no application or
 * infrastructure concerns.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Service {}
