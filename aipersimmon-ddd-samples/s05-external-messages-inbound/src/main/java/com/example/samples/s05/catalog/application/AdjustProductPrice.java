package com.example.samples.s05.catalog.application;

import com.aipersimmon.ddd.cqrs.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Apply a <em>relative</em> price change the upstream asked for — the case where a dedup key is not
 * optional.
 *
 * <p>{@code upstreamMessageId} is that key, and it comes from the upstream because <strong>nowhere else
 * can it come from</strong>. Two genuine "-5%" adjustments and one adjustment delivered twice are the
 * same bytes; only the producer knows which it sent. The tempting substitutes are all wrong in a way
 * that is invisible until it matters:
 *
 * <ul>
 *   <li><strong>a hash of the payload</strong> — collapses two legitimate identical adjustments into
 *       one, and breaks the moment the producer re-serialises with different key order or whitespace;
 *   <li><strong>the Kafka coordinates</strong> {@code (topic, partition, offset)} — not stable across a
 *       producer retry, a topic migration or a replay, all of which are the exact events dedup exists
 *       for;
 *   <li><strong>an id minted on arrival</strong> — a new one per delivery, so it suppresses nothing.
 * </ul>
 *
 * <p>So if the upstream cannot supply one, this message is not consumable: the adapter rejects it rather
 * than guess (see {@code ErpProductMessageListener}). The alternative — make the effect absolute, as
 * {@link MirrorProductChange} is — is a change to the contract, and it is the one worth negotiating for.
 */
public record AdjustProductPrice(
    @NotBlank String sku, @Positive int reductionPercent, @NotBlank String upstreamMessageId)
    implements Command<Boolean> {}
