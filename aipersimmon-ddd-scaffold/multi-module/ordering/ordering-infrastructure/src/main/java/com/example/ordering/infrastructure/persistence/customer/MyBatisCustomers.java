package com.example.ordering.infrastructure.persistence.customer;

import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.shared.Money;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed {@link Customers}, reading the Flyway-seeded {@code ordering.customers}. */
@Repository
public class MyBatisCustomers implements Customers {

    private final CustomerMapper customers;

    public MyBatisCustomers(CustomerMapper customers) {
        this.customers = customers;
    }

    @Override
    public Optional<Customer> findById(CustomerId id) {
        CustomerDo row = customers.selectById(id.value());
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new Customer(
                new CustomerId(row.getId()), row.getName(), Money.of(row.getCreditMinor(), row.getCurrency())));
    }
}
