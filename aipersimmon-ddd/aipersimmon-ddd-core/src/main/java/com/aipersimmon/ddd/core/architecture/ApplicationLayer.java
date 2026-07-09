package com.aipersimmon.ddd.core.architecture;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the annotated package as part of the application layer: use-case
 * orchestration and ports. It depends on the domain layer only, and never on the
 * infrastructure or interface layers.
 */
@Documented
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApplicationLayer {
}
