/**
 * Application-tier building blocks: the {@link com.aipersimmon.ddd.application.DomainEvents}
 * port a use case publishes an aggregate's recorded events through, the
 * {@link com.aipersimmon.ddd.application.UseCase} marker, and the
 * {@link com.aipersimmon.ddd.application.ApplicationException} base. Framework-free:
 * these are ports and markers the infrastructure implements or reads.
 */
package com.aipersimmon.ddd.application;
