package com.example.ordering.infrastructure.persistence.customer;

import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.shared.Money;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory {@link Customers} implementation, seeded with one demo customer. */
@Component
public class InMemoryCustomers implements Customers {

    private final Map<String, Customer> store = new ConcurrentHashMap<>();

    public InMemoryCustomers() {
        Customer seed = new Customer(new CustomerId("CUST-1"), "Acme", Money.of(100_000, "USD"));
        store.put("CUST-1", seed);
    }

    @Override
    public Optional<Customer> findById(CustomerId id) {
        return Optional.ofNullable(store.get(id.value()));
    }
}
