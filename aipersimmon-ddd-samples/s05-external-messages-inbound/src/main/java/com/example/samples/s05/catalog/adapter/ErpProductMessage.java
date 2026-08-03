package com.example.samples.s05.catalog.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The ERP's message, in the ERP's words. The only class in this service allowed to look like this.
 *
 * <p>Everything about it is somebody else's decision: {@code sku_id} rather than a sku, a price as a
 * decimal <em>string</em> with a currency beside it, a revision called {@code rev}, an offset timestamp,
 * and a kind discriminator as a bare string. None of that reaches the domain, and keeping the ugliness
 * confined to one record is what "anti-corruption layer" means in practice — the alternative is a
 * {@code price_cents} that is sometimes a string, forever.
 *
 * <p>Unknown properties are ignored (Jackson's default in Boot), which is deliberate rather than lazy:
 * an upstream that adds a field must not break a consumer that does not read it. The mirror image —
 * upstream <em>removing</em> a field this record needs — surfaces as a null and is refused by the
 * translation, loudly, which is the correct asymmetry.
 */
record ErpProductMessage(
    @JsonProperty("event_kind") String eventKind,
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("price") ErpPrice price,
    @JsonProperty("rev") Long revision,
    @JsonProperty("reduction_percent") Integer reductionPercent,
    @JsonProperty("msg_id") String messageId,
    @JsonProperty("changed_at") String changedAt) {

  /** Money the way the ERP sends it: a decimal string, and a currency that has to be checked. */
  record ErpPrice(@JsonProperty("amount") String amount, @JsonProperty("currency") String currency) {}
}
