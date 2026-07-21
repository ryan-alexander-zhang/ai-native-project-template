package com.aipersimmon.ddd.core.architecture;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the annotated package as part of the domain layer: the model and business rules, free of
 * framework and infrastructure concerns. It must not depend on the application, infrastructure, or
 * interface layers.
 */
@Documented
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DomainLayer {}
