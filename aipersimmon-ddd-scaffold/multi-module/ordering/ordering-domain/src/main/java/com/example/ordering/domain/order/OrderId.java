package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.Identifier;

/** Identity of an {@link Order}. An immutable value compared by its wrapped value. */
public record OrderId(String value) implements Identifier {

    public OrderId {
        if (value == null || value.isBlank()) {
            throw new DomainException("order id required");
        }
    }
}
