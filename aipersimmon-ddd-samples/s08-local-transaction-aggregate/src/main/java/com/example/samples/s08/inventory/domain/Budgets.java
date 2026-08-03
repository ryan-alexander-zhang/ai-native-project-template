package com.example.samples.s08.inventory.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The budget port. */
@Repository
public interface Budgets {

  Optional<ReservationBudget> findById(BudgetId id);

  void save(ReservationBudget budget);
}
