/**
 * S14: the operation log, and the one question it cannot be wired without answering.
 *
 * <p>The component records who performed each operation, and it will not start until the application
 * supplies an {@link
 * com.aipersimmon.ddd.operationlog.cqrs.capture.OperationActorResolver} — there is no default, on
 * purpose. {@code Actor resolve()} takes no arguments, which is the contract doing the arguing: an
 * actor the command could supply would be an actor the caller could choose, and an audit trail whose
 * subject is self-declared records nothing worth having.
 *
 * <p>So the actor has to come from a trusted scope, and this package is that scope: a filter binds it
 * at the HTTP boundary and clears it on the way out, and the resolver reads the binding. Everything
 * interesting about S14 follows from what happens when there is no binding — a scheduled job, a relay,
 * a consumer — which is answered here rather than deferred to whichever scenario meets it first.
 *
 * <p>It sits outside {@code ordering} because it is not the ordering context's concern: any context in
 * this deployable would use the same binding, and none of them should know how it is established.
 */
package com.example.samples.s01.audit;
