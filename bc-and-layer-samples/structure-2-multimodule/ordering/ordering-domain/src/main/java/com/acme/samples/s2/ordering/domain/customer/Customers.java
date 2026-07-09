package com.acme.samples.s2.ordering.domain.customer;

import java.util.Optional;

/** Repository port for the Customer aggregate. */
public interface Customers {
    Optional<Customer> byId(String id);
}
