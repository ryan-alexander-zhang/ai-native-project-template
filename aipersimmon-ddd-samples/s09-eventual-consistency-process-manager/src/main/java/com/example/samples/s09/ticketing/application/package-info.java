/**
 * The use cases: one command a client sends, six the coordinator stages as effects, one that arrives from
 * outside mid-flow — and the {@code TicketingProcess} port they all report through.
 *
 * <p>Every handler here has the same two-part shape: change one aggregate, then tell the coordinator what
 * happened. None of them decides what comes next, and none of them dispatches another command. That is
 * the difference between a flow whose order lives in one readable object and one whose order is an
 * emergent property of eight handlers calling each other.
 */
package com.example.samples.s09.ticketing.application;
