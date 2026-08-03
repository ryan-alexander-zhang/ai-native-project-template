package com.example.samples.s08.inventory.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s08.inventory.domain.BudgetId;
import com.example.samples.s08.inventory.domain.Budgets;
import com.example.samples.s08.inventory.domain.ReservationBudget;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The budget adapter. */
@Repository
class MyBatisBudgets extends MybatisPlusAggregateRepository<ReservationBudget, BudgetRow>
    implements Budgets {

  private final BudgetMapper mapper;

  MyBatisBudgets(BudgetMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(ReservationBudget budget) {
    saveAggregate(budget);
  }

  @Override
  public Optional<ReservationBudget> findById(BudgetId id) {
    BudgetRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        ReservationBudget.reconstitute(
            new BudgetId(row.getId()),
            row.getLimitUnits(),
            row.getReservedUnits(),
            row.getVersion()));
  }

  @Override
  protected BudgetRow toRow(ReservationBudget budget) {
    BudgetRow row = new BudgetRow();
    row.setId(budget.id().value());
    row.setLimitUnits(budget.limit());
    row.setReservedUnits(budget.reserved());
    return row;
  }
}
