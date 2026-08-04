package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One chunk of an upload.
 *
 * <p>The chunk number is in the command and not derived from arrival order, which is what allows the chunks to
 * arrive out of order, in parallel, or twice. Arrival order is the client's business; the server only needs to
 * know which piece this is.
 *
 * <p>The checksum is the client's claim about the bytes, and it is checked. Without it a truncated chunk — the
 * common failure of a dropped connection — is indistinguishable from a short one, and the import completes with
 * a hole in it. Note that this is a <em>different</em> guarantee from the idempotency the primary key gives:
 * the key says "we already have chunk 7", the checksum says "and it is the chunk 7 you meant".
 *
 * @param batchId the batch this belongs to
 * @param chunkNumber which chunk, 1-based
 * @param checksum SHA-256 of {@code payload}, hex
 * @param payload the chunk's lines
 */
public record AcceptChunk(
    @NotBlank String batchId,
    @Min(1) int chunkNumber,
    @NotBlank String checksum,
    @NotNull String payload)
    implements Command<Boolean> {}
