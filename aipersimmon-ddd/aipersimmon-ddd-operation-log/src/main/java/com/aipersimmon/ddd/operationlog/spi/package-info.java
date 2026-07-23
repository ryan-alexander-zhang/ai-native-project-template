/**
 * Pluggable SPI of the Operation Log component. {@link
 * com.aipersimmon.ddd.operationlog.spi.FailureClassifier} maps a throwable to a business {@link
 * com.aipersimmon.ddd.operationlog.model.Outcome} and a sanitized failure; the default
 * implementation lives in the engine and a consumer may override it. Framework-free.
 */
package com.aipersimmon.ddd.operationlog.spi;
