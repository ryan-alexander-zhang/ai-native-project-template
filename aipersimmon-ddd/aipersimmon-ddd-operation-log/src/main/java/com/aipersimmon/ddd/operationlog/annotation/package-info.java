/**
 * The {@link com.aipersimmon.ddd.operationlog.annotation.OperationLog} metadata annotation, placed
 * on an application {@code Command} type. It carries no evaluation logic; the CQRS/Spring adapter's
 * annotation compiler turns it into a synthesized definition. Enforced (by ArchUnit) to appear only
 * on {@code Command} types.
 */
package com.aipersimmon.ddd.operationlog.annotation;
