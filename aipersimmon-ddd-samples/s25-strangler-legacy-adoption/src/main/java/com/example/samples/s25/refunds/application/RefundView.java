package com.example.samples.s25.refunds.application;

/**
 * A refund, as the new context reports it.
 *
 * <p>{@code publicId} first and {@code id} present but named for what it is. During the overlap both exist and callers
 * of the old path pass the number, so hiding it would be a fiction; after the overlap the number goes and this record
 * loses a field, which is a change to one file.
 */
public record RefundView(
    String publicId, long id, long orderId, long amountCents, String state, String approvedBy) {}
