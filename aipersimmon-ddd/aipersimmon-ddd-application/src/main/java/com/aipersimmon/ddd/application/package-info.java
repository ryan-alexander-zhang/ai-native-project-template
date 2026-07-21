/**
 * Application-tier building blocks: the {@link com.aipersimmon.ddd.application.DomainEvents} port a
 * use case publishes an aggregate's recorded events through, the {@link
 * com.aipersimmon.ddd.application.UseCase} marker, and the {@link
 * com.aipersimmon.ddd.application.ApplicationException} base (with the {@link
 * com.aipersimmon.ddd.application.EntityNotFoundException} and {@link
 * com.aipersimmon.ddd.application.ConcurrencyConflictException} use-case failures). Framework-free:
 * these are ports and markers the infrastructure implements or reads.
 */
package com.aipersimmon.ddd.application;
