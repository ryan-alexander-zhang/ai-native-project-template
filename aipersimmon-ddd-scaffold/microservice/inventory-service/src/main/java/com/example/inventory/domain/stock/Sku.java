package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.Identifier;

/** Stock-keeping unit: the identity of a {@link Stock}. */
public record Sku(String value) implements Identifier {

    public Sku {
        if (value == null || value.isBlank()) {
            throw new DomainException("sku required");
        }
    }
}
