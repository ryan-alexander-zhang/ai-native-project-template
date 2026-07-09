package com.acme.samples.s2.ordering.infrastructure;

import com.acme.samples.s2.ordering.domain.customer.Customer;
import com.acme.samples.s2.ordering.domain.customer.Customers;
import com.acme.samples.s2.shared.Money;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CustomerRepository implements Customers {

    private final CustomerMapper customerMapper;

    public CustomerRepository(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public Optional<Customer> byId(String id) {
        CustomerPo po = customerMapper.selectById(id);
        if (po == null) return Optional.empty();
        return Optional.of(new Customer(po.getId(), po.getName(), Money.usd(po.getCreditLimitMinor())));
    }
}
