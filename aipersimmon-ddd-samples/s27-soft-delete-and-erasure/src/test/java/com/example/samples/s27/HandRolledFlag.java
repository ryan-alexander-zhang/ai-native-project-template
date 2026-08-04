package com.example.samples.s27;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.samples.s27.customer.domain.Customer;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The same table, mapped by a row class that maintains its delete flag <strong>by hand</strong> instead of
 * declaring it with {@code @TableLogic}. <strong>Test scope only.</strong>
 *
 * <p>This is the shape a team arrives at when they decide the flag is "just a column": no annotation, no global
 * config, set it and filter on it yourself. It reads as the simpler option, and it collides with how the library
 * writes an aggregate root — {@code ClearedColumnsTest} measures the collision.
 *
 * <p>Two MyBatis-Plus entity classes over one table is legal (the metadata is keyed by class), which is what
 * makes the comparison possible without a second schema.
 */
@TestConfiguration(proxyBeanMethods = false)
public class HandRolledFlag {

  @Bean
  HandRolledCustomers handRolledCustomers(HandRolledMapper mapper, DomainEvents domainEvents) {
    return new HandRolledCustomers(mapper, domainEvents);
  }

  /** No {@code @TableLogic} anywhere. */
  @TableName("s27_customer")
  public static class HandRolledRow implements VersionedRow {

    @TableId(type = IdType.INPUT)
    private String id;

    private String email;
    private String displayName;
    private String phone;
    private String status;
    private String closedReason;
    private Instant erasedAt;

    /** An ordinary column, as far as MyBatis-Plus and the library are concerned. */
    private Boolean deleted;

    @Version private Long version;

    public void setId(String id) {
      this.id = id;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public void setDisplayName(String displayName) {
      this.displayName = displayName;
    }

    public void setPhone(String phone) {
      this.phone = phone;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public void setClosedReason(String closedReason) {
      this.closedReason = closedReason;
    }

    public void setErasedAt(Instant erasedAt) {
      this.erasedAt = erasedAt;
    }

    public Boolean getDeleted() {
      return deleted;
    }

    public void setDeleted(Boolean deleted) {
      this.deleted = deleted;
    }

    @Override
    public Long getVersion() {
      return version;
    }

    @Override
    public void setVersion(Long version) {
      this.version = version;
    }
  }

  @Mapper
  public interface HandRolledMapper extends BaseMapper<HandRolledRow> {}

  /**
   * A repository whose {@code toRow} maps everything the aggregate owns — which does not include the delete
   * flag, because the aggregate has never heard of it. Exactly the same omission as the real repository, and
   * without the annotation it means something else entirely.
   */
  public static class HandRolledCustomers
      extends MybatisPlusAggregateRepository<Customer, HandRolledRow> {

    HandRolledCustomers(HandRolledMapper mapper, DomainEvents domainEvents) {
      super(mapper, domainEvents);
    }

    public void save(Customer customer) {
      saveAggregate(customer);
    }

    @Override
    protected HandRolledRow toRow(Customer customer) {
      HandRolledRow row = new HandRolledRow();
      row.setId(customer.id().value());
      row.setEmail(customer.email().value());
      row.setDisplayName(customer.displayName());
      row.setPhone(customer.phone().orElse(null));
      row.setStatus(customer.status().name());
      row.setClosedReason(customer.closedReason().orElse(null));
      row.setErasedAt(customer.erasedAt().orElse(null));
      // No setDeleted — same as the real one. See ClearedColumnsTest for what that costs here.
      return row;
    }
  }
}
