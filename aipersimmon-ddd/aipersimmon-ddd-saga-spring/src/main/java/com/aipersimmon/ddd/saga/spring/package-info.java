/**
 * Spring implementation of the saga contracts for the optional lightweight orchestration tier.
 *
 * <p>Two {@link com.aipersimmon.ddd.saga.DeadlineScheduler} implementations, selected by {@code
 * aipersimmon.ddd.saga.deadline.store}: {@link
 * com.aipersimmon.ddd.saga.spring.SchedulingDeadlineScheduler} (default) fires a saga's timeouts
 * through a {@link org.springframework.scheduling.TaskScheduler} and dispatches them to the
 * application's {@link com.aipersimmon.ddd.saga.DeadlineHandler}, but its pending timers are
 * in-process and do not survive a restart; {@link
 * com.aipersimmon.ddd.saga.spring.JdbcDeadlineScheduler} instead persists deadlines in a table and
 * fires them from a scheduled poll (the outbox's persist-poll-delete mechanism applied to
 * timeouts), so they survive a restart and can be handled across instances. {@link
 * com.aipersimmon.ddd.saga.spring.JdbcSagaStore} is an abstract {@link
 * com.aipersimmon.ddd.saga.SagaStore} that owns the correlation-id lookup and the version-checked
 * upsert (raising {@link org.springframework.dao.OptimisticLockingFailureException} on a concurrent
 * advance), leaving only the concrete row mapping to a bounded context's subclass. {@link
 * com.aipersimmon.ddd.saga.spring.AipersimmonDddSagaAutoConfiguration} wires the scheduler when a
 * {@code DeadlineHandler} is present.
 */
package com.aipersimmon.ddd.saga.spring;
