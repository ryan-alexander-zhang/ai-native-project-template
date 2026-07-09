/**
 * Framework-free saga / process-manager contracts for coordinating a multi-step,
 * cross-aggregate flow from one place.
 *
 * <p>A type annotated {@link com.aipersimmon.ddd.saga.ProcessManager} coordinates
 * the flow; its persisted state extends {@link com.aipersimmon.ddd.saga.SagaState},
 * which carries a correlation id (used to route each incoming event to the right
 * instance) and a {@link com.aipersimmon.ddd.saga.SagaStatus} whose transitions it
 * guards. A {@link com.aipersimmon.ddd.saga.SagaStore} loads and saves instances by
 * correlation id (with optimistic locking); a
 * {@link com.aipersimmon.ddd.saga.DeadlineScheduler} registers and cancels
 * {@link com.aipersimmon.ddd.saga.Deadline}s and, when one is due, dispatches it to
 * a {@link com.aipersimmon.ddd.saga.DeadlineHandler}.
 *
 * <p>Choreography (participants reacting to events with no coordinator) is the
 * default; this module is for flows that have grown enough steps, branches, or
 * timeouts to warrant an explicit state machine. The contracts are engine-neutral,
 * so a flow can move to a durable-execution engine by swapping the implementation
 * rather than rewriting the flow. A Spring implementation lives in a separate
 * starter.
 */
package com.aipersimmon.ddd.saga;
