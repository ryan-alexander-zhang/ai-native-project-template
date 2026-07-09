package com.aipersimmon.ddd.core.architecture;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the annotated package as part of the interface layer: the inbound
 * adapters and delivery mechanisms (web controllers, messaging listeners, RPC
 * endpoints) that drive use cases. It depends inward on the application and
 * domain layers.
 */
@Documented
@Target(ElementType.PACKAGE)
@Retention(RetentionPolicy.RUNTIME)
public @interface InterfaceLayer {
}
