package com.example.ordering.domain.shared;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * A stock-keeping unit, as ordering understands it.
 *
 * <p>This context has a second type of the same name in {@code inventory-domain}, and that is the
 * point rather than an oversight. SKU is <em>defined</em> by inventory — what one is, which ones
 * exist, when one is retired — and ordering only refers to it. Two contexts modelling a shared
 * concept each in their own terms is the ordinary DDD answer; lifting one type into a shared module
 * so both can use it is the thing to avoid, because it couples the two to a single definition that
 * has to satisfy both, and the {@code *-api} split exists precisely so they need not be.
 *
 * <p>Note what is <em>not</em> shared with inventory's version: it implements {@code Identifier}
 * there, because a SKU is the identity of its {@code Stock} aggregate. Here it identifies nothing
 * ordering owns — it is a value an order line carries — so it is a plain value object. The
 * difference is the same concept seen from two sides.
 *
 * <p>Before this type, ordering carried SKU as a bare {@code String} while modelling {@code
 * OrderId} and {@code CustomerId} as records right beside it. That cost two things worth naming:
 * the blank check lived both here and in {@code OrderLine}'s constructor, free to drift apart, and
 * {@code ManualReviewPolicy}'s watchlist was a {@code Set<String>} that the type system could not
 * tell from a set of customer ids.
 *
 * <p>It stops at the context boundary. {@code OrderReadyForFulfilment} and the stock-availability
 * gateway both carry plain strings, because a published contract should be flat — a consumer must
 * not have to depend on ordering's types to read ordering's events.
 */
@ValueObject
public record Sku(String value) {

  public Sku {
    if (value == null || value.isBlank()) {
      throw new DomainException("sku required");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
