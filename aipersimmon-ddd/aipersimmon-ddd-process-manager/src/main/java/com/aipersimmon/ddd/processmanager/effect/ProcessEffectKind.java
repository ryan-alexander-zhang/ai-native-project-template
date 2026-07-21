package com.aipersimmon.ddd.processmanager.effect;

/**
 * The kind of a {@link ProcessEffect}, used by a runtime to route each effect to the one dispatcher
 * registered for it. Distinct from the effect's Java type so a runtime can index dispatchers
 * without a {@code instanceof} cascade.
 */
public enum ProcessEffectKind {

  /** Send a command to its handler; see {@link DispatchCommand}. */
  DISPATCH_COMMAND,
  /** Publish an integration event; see {@link PublishIntegrationEvent}. */
  PUBLISH_INTEGRATION_EVENT,
  /** Arm or reschedule a named timer; see {@link ScheduleDeadline}. */
  SCHEDULE_DEADLINE,
  /** Cancel a named timer's current generation; see {@link CancelDeadline}. */
  CANCEL_DEADLINE
}
