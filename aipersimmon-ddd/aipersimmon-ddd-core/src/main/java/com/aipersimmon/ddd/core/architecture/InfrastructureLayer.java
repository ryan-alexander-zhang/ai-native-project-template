package com.aipersimmon.ddd.core.architecture;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the annotated package as part of the infrastructure layer: technical
 * implementations of domain and application ports, such as persistence,
 * messaging, and external clients. It depends inward on the domain and
 * application layers.
 */
@Documented
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.RUNTIME)
public @interface InfrastructureLayer {
}
