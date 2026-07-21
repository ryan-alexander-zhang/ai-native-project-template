/**
 * The process-runtime exception family, rooted at {@link
 * com.aipersimmon.ddd.processmanager.exception.ProcessException}: identity ({@link
 * com.aipersimmon.ddd.processmanager.exception.ProcessAlreadyExistsException}, {@link
 * com.aipersimmon.ddd.processmanager.exception.ProcessNotFoundException}, {@link
 * com.aipersimmon.ddd.processmanager.exception.UnknownProcessDefinitionException}), input validity
 * ({@link com.aipersimmon.ddd.processmanager.exception.UnsupportedProcessInputException}),
 * concurrency ({@link com.aipersimmon.ddd.processmanager.exception.StaleProcessRevisionException}),
 * operational state ({@link
 * com.aipersimmon.ddd.processmanager.exception.ProcessSuspendedException}), and serialization
 * ({@link com.aipersimmon.ddd.processmanager.exception.ProcessSerializationException}). These are
 * runtime-coordination failures, distinct from a bounded context's domain exceptions.
 */
package com.aipersimmon.ddd.processmanager.exception;
