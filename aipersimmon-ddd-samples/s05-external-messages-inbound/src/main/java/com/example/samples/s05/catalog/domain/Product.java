package com.example.samples.s05.catalog.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * A product, mastered upstream and mirrored here.
 *
 * <p><strong>{@code upstreamRevision} is the whole answer to out-of-order delivery</strong>, and it is
 * domain state rather than plumbing: "which version of the upstream truth am I holding" is a fact about
 * this product that outlives any particular message. Keeping it here — instead of in a listener, a
 * table column nobody reads, or a Redis key — is what makes the ordering rule enforceable by the
 * aggregate that owns the data.
 *
 * <p>{@link #applyUpstreamChange} is <strong>last-writer-wins by revision, not by arrival</strong>. An
 * older revision is not an error and not a duplicate: it is a message that has been overtaken, so it is
 * reported as {@link ChangeOutcome#SUPERSEDED} and dropped. That single comparison covers both hazards
 * the catalogue separates — a redelivery carries a revision that is no longer greater, and so does a
 * message that arrived late — which is why an absolute-state message needs no dedup key at all.
 *
 * <p>What it does <em>not</em> cover: a message whose effect is relative rather than absolute. See
 * {@link #adjustPriceBy}.
 */
@AggregateRoot
public final class Product extends AbstractAggregateRoot<Sku> {

  private final Sku sku;
  private String name;
  private long priceCents;
  private long upstreamRevision;

  private Product(Sku sku, String name, long priceCents, long upstreamRevision) {
    this.sku = sku;
    this.name = name;
    this.priceCents = priceCents;
    this.upstreamRevision = upstreamRevision;
  }

  /** First sighting: master data arrives as an upsert, because a mirror has no "create" of its own. */
  public static Product mirrored(Sku sku, String name, long priceCents, long upstreamRevision) {
    Product product = new Product(sku, name, priceCents, upstreamRevision);
    product.checkInvariant(new PriceIsNotNegative(priceCents));
    return product;
  }

  public static Product reconstitute(
      Sku sku, String name, long priceCents, long upstreamRevision, long version) {
    Product product = new Product(sku, name, priceCents, upstreamRevision);
    product.restoreVersion(version);
    return product;
  }

  /**
   * Applies an upstream change if it is newer than what this product already holds.
   *
   * @return {@link ChangeOutcome#UPDATED} when applied, {@link ChangeOutcome#SUPERSEDED} when the
   *     incoming revision is not greater — a duplicate or a late message, treated the same because
   *     they are the same thing from here: news this product already has.
   */
  public ChangeOutcome applyUpstreamChange(long revision, String name, long priceCents) {
    if (revision <= upstreamRevision) {
      return ChangeOutcome.SUPERSEDED;
    }
    checkInvariant(new PriceIsNotNegative(priceCents));
    this.name = name;
    this.priceCents = priceCents;
    this.upstreamRevision = revision;
    return ChangeOutcome.UPDATED;
  }

  /**
   * A <em>relative</em> change, which is the case the revision guard cannot save.
   *
   * <p>The upstream sends "reduce by 5%", not "the price is now 950". There is no content to compare
   * against what is already stored — applying it twice is indistinguishable from applying two genuine
   * adjustments — so protecting it needs an explicit dedup key naming the message, recorded in the same
   * transaction as the effect. That is the application's job, not the aggregate's: the aggregate simply
   * does what it is told, and the price is what it says.
   */
  public void adjustPriceBy(int percent) {
    long adjusted = priceCents + (priceCents * percent) / 100;
    checkInvariant(new PriceIsNotNegative(adjusted));
    this.priceCents = adjusted;
  }

  @Override
  public Sku id() {
    return sku;
  }

  public String name() {
    return name;
  }

  public long priceCents() {
    return priceCents;
  }

  public long upstreamRevision() {
    return upstreamRevision;
  }
}
