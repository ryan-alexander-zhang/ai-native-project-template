package com.aipersimmon.ddd.processmanager.definition;

/**
 * A message a process reacts to: the event of starting, a result fact from a command
 * or integration event, a fired deadline, or a business request. The concrete inputs
 * are a sealed hierarchy owned by the consumer's process (the framework does not
 * define business inputs); this marker only lets the runtime and effects refer to
 * "an input" generically. Like a command payload, an input carries business fields
 * only — never transport metadata.
 */
public interface ProcessInput {
}
