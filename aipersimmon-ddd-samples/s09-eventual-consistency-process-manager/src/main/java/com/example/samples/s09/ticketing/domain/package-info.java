/**
 * The three aggregates the flow coordinates, and none of them knows it is being coordinated.
 *
 * <p>Read for what is absent: no step, no saga id, no "awaiting" status, no reference to the process
 * manager. Every operation the coordinator will call is idempotent and returns an outcome rather than
 * throwing, because those are the two properties a participant in an at-least-once flow needs — and both
 * are properties worth having anyway.
 */
package com.example.samples.s09.ticketing.domain;
