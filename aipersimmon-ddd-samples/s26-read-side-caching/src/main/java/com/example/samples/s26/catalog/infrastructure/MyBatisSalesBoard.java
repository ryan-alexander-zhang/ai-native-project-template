package com.example.samples.s26.catalog.infrastructure;

import com.example.samples.s26.catalog.application.SalesBoard;
import com.example.samples.s26.catalog.application.TopSeller;
import com.example.samples.s26.catalog.domain.Sku;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The projection, in Postgres. */
@Repository
class MyBatisSalesBoard implements SalesBoard {

  private final SalesBoardMapper mapper;

  MyBatisSalesBoard(SalesBoardMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void add(Sku sku, int quantity) {
    mapper.add(sku.value(), quantity);
  }

  /**
   * Clear, then recompute — in the caller's transaction, which is what makes it safe to run on a live
   * service.
   *
   * <p>The two statements are one atomic outcome, so no reader ever sees the empty table between them: they
   * see the old projection or the new one. Run outside a transaction, the same two statements are a window
   * in which the best-sellers list is empty, and the length of that window is the length of the rebuild.
   * The command bus opens the transaction, so this is inherited rather than remembered — but only because
   * the rebuild goes through a command instead of being a maintenance script.
   */
  @Override
  public int rebuild(Duration window) {
    mapper.clear();
    return mapper.rebuildSince(Instant.now().minus(window));
  }

  @Override
  public Optional<Long> soldRecently(Sku sku) {
    return Optional.ofNullable(mapper.soldRecently(sku.value()));
  }

  @Override
  public List<TopSeller> top(int limit) {
    return mapper.top(limit).stream()
        .map(row -> new TopSeller(row.getSku(), row.getName(), row.getSoldRecently()))
        .toList();
  }
}
