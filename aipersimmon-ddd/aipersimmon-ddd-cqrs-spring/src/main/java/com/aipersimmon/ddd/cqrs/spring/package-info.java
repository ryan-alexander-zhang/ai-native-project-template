/**
 * Spring implementation of the CQRS contracts.
 *
 * <p>{@link com.aipersimmon.ddd.cqrs.spring.RegistryCommandBus} routes each command
 * to its single registered handler and applies the ordered interceptor chain;
 * {@link com.aipersimmon.ddd.cqrs.spring.RegistryQueryBus} does the same for
 * queries. The built-in interceptors are
 * {@link com.aipersimmon.ddd.cqrs.spring.LoggingCommandInterceptor} (outermost),
 * {@link com.aipersimmon.ddd.cqrs.spring.ValidationCommandInterceptor} (when a Bean
 * Validation provider is present), and
 * {@link com.aipersimmon.ddd.cqrs.spring.TransactionCommandInterceptor} (innermost),
 * which runs the handler in a
 * {@link com.aipersimmon.ddd.cqrs.spring.TransactionTemplateUnitOfWork}. An
 * aggregate's recorded events are drained where it is saved (via
 * {@link com.aipersimmon.ddd.application.DomainEvents#publishAndClear}) within that
 * transaction, so no thread-scoped collector is involved.
 * {@link com.aipersimmon.ddd.cqrs.spring.AipersimmonDddCqrsAutoConfiguration}
 * wires it all, each bean overridable by the application.
 */
package com.aipersimmon.ddd.cqrs.spring;
