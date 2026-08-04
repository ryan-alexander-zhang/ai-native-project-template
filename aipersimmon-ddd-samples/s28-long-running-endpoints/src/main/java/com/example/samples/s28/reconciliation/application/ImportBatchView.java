package com.example.samples.s28.reconciliation.application;

import com.example.samples.s28.reconciliation.domain.ImportStatus;
import java.time.Instant;
import java.util.List;

/**
 * The upload resource, and the field that makes an upload resumable: {@code missingChunks}.
 *
 * <p>A client that lost its connection does not know how much of what it sent arrived. Being told which chunks
 * are still wanted is the difference between resuming and starting again — and it has to be the server's answer,
 * because the client's record of what it sent is exactly the record the failure destroyed.
 */
public record ImportBatchView(
    String batchId,
    int declaredChunks,
    ImportStatus status,
    List<Integer> missingChunks,
    long receivedRows,
    long acceptedRows,
    String failure,
    Instant openedAt,
    Instant completedAt) {}
