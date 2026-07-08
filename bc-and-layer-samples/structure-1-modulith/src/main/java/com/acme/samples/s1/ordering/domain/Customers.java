package com.acme.samples.s1.ordering.domain;

import java.util.Optional;

/** Repository port for the Customer aggregate. */
public interface Customers {
    Optional<Customer> byId(String id);
}
