/**
 * Spring implementation of the saga contracts for the optional lightweight
 * orchestration tier.
 *
 * <p>{@link com.aipersimmon.ddd.saga.spring.SchedulingDeadlineScheduler} fires a
 * saga's timeouts through a {@link org.springframework.scheduling.TaskScheduler}
 * and dispatches them to the application's
 * {@link com.aipersimmon.ddd.saga.DeadlineHandler}; being in-process, its pending
 * timers do not survive a restart.
 * {@link com.aipersimmon.ddd.saga.spring.JdbcSagaStore} is an abstract
 * {@link com.aipersimmon.ddd.saga.SagaStore} that owns the correlation-id lookup and
 * the version-checked upsert (raising
 * {@link org.springframework.dao.OptimisticLockingFailureException} on a concurrent
 * advance), leaving only the concrete row mapping to a bounded context's subclass.
 * {@link com.aipersimmon.ddd.saga.spring.AipersimmonDddSagaAutoConfiguration} wires
 * the scheduler when a {@code DeadlineHandler} is present.
 */
package com.aipersimmon.ddd.saga.spring;
