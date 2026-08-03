package com.example.samples.s05.catalog.application;

import com.aipersimmon.ddd.cqrs.Command;
import com.example.samples.s05.catalog.domain.ChangeOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Mirror an upstream product change: absolute state, plus the revision it belongs to.
 *
 * <p>This is a command of <em>this</em> context, in this context's words, and it is what the ERP's
 * message becomes at the boundary. Everything foreign has already been dealt with by the time it exists:
 * the field names, the units, the date format, the extra attributes nobody here needs. So the handler,
 * the aggregate and the interceptor chain are the same ones an HTTP entry would drive — which is the
 * whole reason to translate at the edge rather than pass the ERP's DTO inward.
 *
 * <p><strong>It carries no message id, and that is a claim rather than an omission.</strong> The
 * revision makes the effect idempotent by content: applying revision 7 twice leaves exactly what
 * applying it once left. A dedup key would be dead weight here, and dead weight that has to be right.
 *
 * <p>The constraints are the coarse gate the framework's validation interceptor applies before the
 * handler runs: they reject a message shape no upstream should ever send. Whether the change is
 * <em>welcome</em> — newer than what is held — is a question about state, so it belongs to the
 * aggregate, not here (S19 draws that line properly).
 */
public record MirrorProductChange(
    @NotBlank String sku,
    @Positive long revision,
    @NotBlank String name,
    @PositiveOrZero long priceCents)
    implements Command<ChangeOutcome> {}
