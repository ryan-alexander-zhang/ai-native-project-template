package com.example.samples.s28.reconciliation.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Announce an upload: this many chunks, under this id.
 *
 * <p>Declaring the count up front is what makes completion checkable. The alternative — "tell me when you are
 * done" — cannot distinguish a finished upload from one whose last chunk was lost, so the server has to trust the
 * client's word about its own completeness, which is the one thing a resumable protocol exists not to do.
 *
 * @return whether this call opened it, so a retry is not an error
 */
public record OpenImport(@NotBlank String batchId, @Min(1) int chunks) implements Command<Boolean> {}
