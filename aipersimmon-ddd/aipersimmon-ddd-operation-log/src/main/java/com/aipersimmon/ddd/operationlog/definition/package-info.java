/**
 * The type-safe capture lifecycle: an {@link
 * com.aipersimmon.ddd.operationlog.definition.OperationLogDefinition} captures a before projection
 * in {@code prepare}, classifies the result in {@link
 * com.aipersimmon.ddd.operationlog.definition.PreparedOperationLog#complete}, and describes
 * failures in {@code failed}. The annotation compiler synthesizes a definition too, so both
 * entrances share one lifecycle. Framework-free and CQRS-free.
 */
package com.aipersimmon.ddd.operationlog.definition;
