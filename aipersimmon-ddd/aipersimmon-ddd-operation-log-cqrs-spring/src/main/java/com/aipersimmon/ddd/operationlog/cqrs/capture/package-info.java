/**
 * The capture layer: two {@code CommandInterceptor}s ({@link
 * com.aipersimmon.ddd.operationlog.cqrs.capture.CompletedOperationLogInterceptor} inside the
 * transaction, {@link com.aipersimmon.ddd.operationlog.cqrs.capture.FailedOperationLogInterceptor}
 * outside it), the definition registry, the annotation-compiled definition, the actor/tenant
 * resolver contracts, and the transaction seams used by the failed path (transaction-state
 * predicate, independent-transaction runner, completion policy).
 */
package com.aipersimmon.ddd.operationlog.cqrs.capture;
