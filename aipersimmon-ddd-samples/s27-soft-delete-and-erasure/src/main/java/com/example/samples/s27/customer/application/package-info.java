/**
 * The use cases, including the one that has to reckon with everything else in the schema.
 *
 * <p>Five of the six commands here are ordinary. {@code EraseCustomer} is not: it is the only command in any
 * sample in this series that has to know about the outbox, and it refuses rather than proceeding when the
 * queue is not drained. That coupling is real and is argued for in the handler — an erasure is an ordering
 * problem before it is a data problem.
 */
package com.example.samples.s27.customer.application;
