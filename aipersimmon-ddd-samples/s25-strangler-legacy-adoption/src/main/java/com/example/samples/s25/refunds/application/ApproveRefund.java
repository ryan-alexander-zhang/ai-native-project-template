package com.example.samples.s25.refunds.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Approve one.
 *
 * <p>Where the legacy method returned {@code void} and swallowed "already approved", this refuses. That is a behaviour
 * change on a path that already had callers, and it is the one this extraction makes on purpose — so it is worth being
 * explicit that it is not free: a caller that used to approve twice and never be told will now see a 409.
 * {@code StranglerTest} asserts both the refusal and what the legacy path used to do, so the difference is written
 * down rather than discovered.
 */
public record ApproveRefund(@Min(1) long refundId, @NotBlank String approvedBy)
    implements Command<Void> {}
