package com.acme.samples.s3.ordering.domain;

import java.util.Optional;

public interface Customers {
    Optional<Customer> byId(String id);
}
