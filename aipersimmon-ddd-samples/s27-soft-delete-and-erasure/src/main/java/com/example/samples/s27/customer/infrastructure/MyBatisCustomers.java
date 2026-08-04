package com.example.samples.s27.customer.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s27.customer.domain.Customer;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.CustomerStatus;
import com.example.samples.s27.customer.domain.Customers;
import com.example.samples.s27.customer.domain.EmailAddress;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The write path, and the three deletions as they look from here.
 *
 * <p>{@link #find} does nothing special and still cannot see a suppressed row: the filter is in the SQL
 * MyBatis-Plus generates, not in this class. {@link #suppress} calls {@code deleteById}, which the annotation
 * turns into an {@code UPDATE ... SET deleted = true}. {@link #restore} needs hand-written SQL, because nothing
 * in the generated API can address a row it is built to hide.
 *
 * <p>{@link #toRow} maps every field the aggregate owns and <strong>not</strong> {@code deleted}. That omission
 * is safe only because of the annotation — see {@code CustomerRow} and {@code ClearedColumnsTest}.
 */
@Repository
class MyBatisCustomers extends MybatisPlusAggregateRepository<Customer, CustomerRow>
    implements Customers {

  private final CustomerMapper mapper;

  MyBatisCustomers(CustomerMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(Customer customer) {
    saveAggregate(customer);
  }

  @Override
  public Optional<Customer> find(CustomerId id) {
    CustomerRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Customer.reconstitute(
            id,
            new EmailAddress(row.getEmail()),
            row.getDisplayName(),
            row.getPhone(),
            CustomerStatus.valueOf(row.getStatus()),
            row.getClosedReason(),
            row.getErasedAt(),
            row.getVersion()));
  }

  /**
   * {@code deleteById} is not a delete here: the annotation rewrites it into an update of the flag.
   *
   * <p>Which is worth one moment's discomfort. The method is called delete, the row survives, and nothing at the
   * call site says so — the behaviour lives on a field annotation two classes away. Anybody reading this line
   * without knowing about {@code @TableLogic} will read it as a destructive operation, and anybody grepping for
   * "who deletes customers" will find it and stop. That is the standing cost of the mechanism, and it is paid in
   * comprehension rather than in correctness.
   */
  @Override
  public boolean suppress(CustomerId id) {
    return mapper.deleteById(id.value()) == 1;
  }

  @Override
  public boolean restore(CustomerId id) {
    return mapper.restoreById(id.value()) == 1;
  }

  @Override
  protected CustomerRow toRow(Customer customer) {
    CustomerRow row = new CustomerRow();
    row.setId(customer.id().value());
    row.setEmail(customer.email().value());
    row.setDisplayName(customer.displayName());
    row.setPhone(customer.phone().orElse(null));
    row.setStatus(customer.status().name());
    row.setClosedReason(customer.closedReason().orElse(null));
    row.setErasedAt(customer.erasedAt().orElse(null));
    // No setDeleted: the aggregate has no opinion about whether its row is visible. See the class javadoc.
    return row;
  }
}
