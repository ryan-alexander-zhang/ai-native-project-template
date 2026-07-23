/**
 * The default {@link com.aipersimmon.ddd.operationlog.spi.FailureClassifier} implementation,
 * aligned with the repository exception model (design-00003): expected {@code DomainException}s
 * become {@code REJECTED}, concurrency conflicts and other technical faults become {@code FAILED}.
 * Consumers may override it.
 */
package com.aipersimmon.ddd.operationlog.engine.classifier;
