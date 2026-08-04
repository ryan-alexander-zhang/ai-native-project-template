package com.example.samples.s28.reconciliation.application;

import com.example.samples.s28.reconciliation.domain.ExportStatus;
import java.time.Instant;

/**
 * The job resource: what a client gets from polling, and the whole asynchronous contract in one record.
 *
 * <p>Five things a poller needs, and each of them is here because leaving it out sends somebody to ask a human:
 *
 * <ul>
 *   <li><strong>status</strong> — the answer to "is it done".
 *   <li><strong>progress</strong> — the answer to "is it stuck", which status cannot give. Null before the
 *       first tick; a client that has to distinguish "no progress yet" from "zero rows" gets to.
 *   <li><strong>attempt</strong> — the answer to "has this been going wrong repeatedly". A job on its fourth
 *       attempt looks identical to a fresh one without it.
 *   <li><strong>failure</strong> — the answer to "why". A FAILED status with nothing else is a support ticket.
 *   <li><strong>contentPath</strong> — where the bytes are, present only once there are bytes. A link that
 *       exists while the job runs is a link somebody will follow.
 * </ul>
 *
 * <p>What is <em>not</em> here: the rows. A job resource that grew to hold its own output would be back to the
 * synchronous endpoint this whole scenario is an alternative to.
 */
public record ExportJobView(
    String exportId,
    String period,
    ExportStatus status,
    int attempt,
    boolean cancelRequested,
    ExportProgress progress,
    Long artifactBytes,
    Long artifactRows,
    String contentPath,
    String failure,
    Instant submittedAt,
    Instant finishedAt) {}
