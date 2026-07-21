package com.aipersimmon.ddd.processmanager.definition;

import com.aipersimmon.ddd.processmanager.model.DeadlineName;

/**
 * The one runtime-level {@link ProcessInput}: the whole-instance max-lifetime backstop fired. It is
 * not a business input — the runtime, not the consumer, schedules the backstop deadline at {@code
 * start} when {@code instance.max-lifetime} is configured, and turns it into this input on expiry
 * so the {@link ProcessDefinition} decides what to do (compensate, fail, or extend by rescheduling
 * the same {@link #DEADLINE_NAME}). A definition that enables the backstop must handle this input;
 * otherwise its {@code react} rejects it and the deadline eventually suspends the instance for
 * operator attention.
 */
public record MaxLifetimeExceeded() implements ProcessInput {

  /**
   * The reserved deadline name the runtime arms for the backstop; reschedule it to extend the TTL.
   */
  public static final DeadlineName DEADLINE_NAME = new DeadlineName("aipersimmon.max-lifetime");
}
