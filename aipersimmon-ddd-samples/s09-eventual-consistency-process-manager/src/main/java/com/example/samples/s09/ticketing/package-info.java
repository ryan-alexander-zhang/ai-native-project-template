/**
 * The ticketing context: it sells one seat at a time, and it takes three separate writes to do it.
 *
 * <p>That is the situation a process manager exists for. There is no transaction that can span the seat
 * counter, the customer's balance and the order, and there is no ordering in which a failure is impossible
 * — so the flow is explicit, durable, and reversible by <em>compensation</em> rather than by rollback.
 */
package com.example.samples.s09.ticketing;
