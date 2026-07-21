/**
 * The deadline worker that fires due timers. {@link
 * com.aipersimmon.ddd.processmanager.jdbc.deadline.JdbcProcessDeadlineWorker} claims due deadlines
 * of active instances, turns each into an ordinary process input, and re-enters the runtime's
 * {@code handle} — recording the transition and marking the deadline {@code FIRED} in one
 * transaction. A superseded generation is an auditable no-op; exhausted retries move the deadline
 * to DEAD and suspend the instance.
 */
package com.aipersimmon.ddd.processmanager.jdbc.deadline;
