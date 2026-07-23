/**
 * Operator recovery, independent of the business runtime: {@link
 * com.aipersimmon.ddd.processmanager.engine.operation.ProcessOperations} redrives a DEAD effect
 * (resuming the instance and replaying parked inputs when it was the last blocker) and cancels a
 * coordinator (terminating it and cancelling pending effects and deadlines, without sending
 * compensation). Every action is audited as an operator transition; none edits state or step
 * arbitrarily.
 */
package com.aipersimmon.ddd.processmanager.engine.operation;
