/**
 * The anticorruption layer, both directions of it.
 *
 * <p>Six classes and one boundary. Everything in this package knows that a payment provider exists, speaks
 * {@code merchant_ref} and {@code result_code}, and has opinions about HTTP status codes. Nothing outside it
 * does — the wire records and the code table are package-private, so that claim is enforced by the compiler
 * rather than by an ArchUnit rule that has to be told which classes to watch.
 *
 * <p>It contains a {@code @RestController}, which departs from where the other samples put one. The
 * reasoning is in {@code GatewayCallbackController}: a callback endpoint is not our API, it is the return
 * path of an outbound call, and it would not survive changing providers. Putting it here is what lets both
 * directions share one code table, which is the piece that must not drift.
 *
 * <p>What it deliberately does <em>not</em> contain: any decision about a payment. Both roads out of this
 * package lead to a command.
 */
package com.example.samples.s07.payments.infrastructure.gateway;
