package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * Inventory's reference to the order a reservation holds stock for.
 *
 * <p>Deliberately a local type, not ordering's {@code OrderId}: importing that would couple the
 * contexts at the type level, which the DDL already refuses (no foreign key crosses the schema
 * boundary) and {@code ArchitectureTest} enforces on the Java side. But a bare {@code String} was
 * the opposite mistake — the one id in this context that could be confused with any other string,
 * in the context whose whole compensation path hangs off it. A reference this load-bearing gets a
 * name and a guard.
 */
@ValueObject
public record OrderRef(String value) {

  public OrderRef {
    if (value == null || value.isBlank()) {
      throw new DomainException("an order reference must name an order");
    }
  }
}
