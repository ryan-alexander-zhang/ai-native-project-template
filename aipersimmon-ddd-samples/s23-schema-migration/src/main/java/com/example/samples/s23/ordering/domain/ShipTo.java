package com.example.samples.s23.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * Where the order goes, as two fields — which is what V2 and V3 of the ordering migrations were for.
 *
 * <p>Worth noticing what the split bought, because "structure is better than free text" is not an argument.
 * It bought exactly one thing: {@link Handling} can be decided from the city. Before the split that
 * decision was impossible without parsing a string at read time, every time, differently in each place
 * that needed it. A structural migration that enables no new decision is a migration that costs three
 * deploys and buys tidiness.
 */
@ValueObject
public record ShipTo(String street, String city) {

  public ShipTo {
    if (street == null || street.isBlank()) {
      throw new IllegalArgumentException("street must not be blank");
    }
    if (city == null || city.isBlank()) {
      throw new IllegalArgumentException("city must not be blank");
    }
  }
}
