package com.example.ordering.domain.customer;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** Repository port for the Customer aggregate; implemented in the infrastructure layer. */
@Repository
public interface Customers {

    Optional<Customer> findById(CustomerId id);
}
