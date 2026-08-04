/**
 * S28: the endpoint that cannot answer inside a request.
 *
 * <p>Four questions, and the sample is arranged so that each is measured rather than asserted:
 *
 * <ul>
 *   <li><strong>Where is the synchronous limit?</strong> Not at any HTTP timeout — at the connection pool. One slow
 *       streaming endpoint holds one connection for its whole duration, and the library imposes no time limit of its
 *       own on a command, a query or a handler. {@code SynchronousLimitTest}.
 *   <li><strong>What does the asynchronous contract look like?</strong> {@code PUT} with a client-supplied id → 202
 *       + {@code Location}; one pollable job resource carrying status, progress, attempt and failure; the content
 *       link appearing only when there is content. {@code ExportController}, {@code JobContractTest}.
 *   <li><strong>Should the job be an aggregate?</strong> Its lifecycle, yes — every transition is a rule. Its
 *       progress, no, and putting it there is the mistake that makes cancellation collide with a counter.
 *       {@code ExportJob}, {@code ProgressIsNotAnInvariantTest}.
 *   <li><strong>Why not the process manager?</strong> Because a job queue needs the four things it deliberately does
 *       not have, and the sample measures each against the real engine rather than citing its javadoc. The positive
 *       alternative is the claim-with-a-lease that the library already uses for its own relays.
 *       {@code NotAJobQueueTest}.
 * </ul>
 *
 * <p>Plus the two mechanical facts a large export stands on: a server-side cursor needs a transaction <em>and</em> a
 * fetch size, and either one missing is silent; and a single-snapshot read and a keyset-paged read are not the same
 * export. {@code StreamingExportTest} measures both.
 */
package com.example.samples.s28;
