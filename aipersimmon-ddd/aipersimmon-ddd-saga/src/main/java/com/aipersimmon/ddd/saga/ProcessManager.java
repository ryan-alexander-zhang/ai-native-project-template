package com.aipersimmon.ddd.saga;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as a process manager (also called a saga): the central coordinator
 * of a multi-step, cross-aggregate flow. It holds the flow's state, reacts to the
 * events that advance it, issues the next commands, and drives compensation when a
 * step fails — as opposed to choreography, where each participant reacts to events
 * with no coordinator. Its persisted state is a {@link SagaState}.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProcessManager {
}
