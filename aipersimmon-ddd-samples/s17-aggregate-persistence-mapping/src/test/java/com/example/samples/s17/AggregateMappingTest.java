package com.example.samples.s17;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.DuplicateEntityException;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.example.samples.s17.ordering.domain.LineId;
import com.example.samples.s17.ordering.domain.Money;
import com.example.samples.s17.ordering.domain.Order;
import com.example.samples.s17.ordering.domain.OrderId;
import com.example.samples.s17.ordering.domain.OrderStatus;
import com.example.samples.s17.ordering.domain.Orders;
import com.example.samples.s17.ordering.domain.ShippingAddress;
import com.example.samples.s17.ordering.infrastructure.NaiveOrderWriter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Every claim the companion document makes about the write path, pinned against a real PostgreSQL. */
@SpringBootTest
@Import(PostgresServiceConnection.class)
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class AggregateMappingTest {

  private static final ShippingAddress ADDRESS =
      new ShippingAddress("Ada", "1 Analytical Way", "London", "E1 6AN");
  private static final Money PRICE = Money.of("CNY", 1000);

  @Autowired private Orders orders;
  @Autowired private NaiveOrderWriter naive;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate tx;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM s17_order_line");
    jdbc.update("DELETE FROM s17_order");
  }

  @Test
  void aSavedAggregateComesBackWhole() {
    OrderId id = new OrderId("order-1");
    tx.executeWithoutResult(
        status -> {
          Order order = Order.draft(id, "customer-1", ADDRESS);
          order.addLine(new LineId("line-1"), "SKU-1", PRICE, 2);
          order.noteFor("leave at the door");
          orders.save(order);
        });

    Order loaded = tx.execute(status -> orders.findById(id).orElseThrow());

    assertThat(loaded.customerId()).isEqualTo("customer-1");
    assertThat(loaded.status()).isEqualTo(OrderStatus.DRAFT);
    assertThat(loaded.note()).isEqualTo("leave at the door");
    // A value object round-tripped through a single JSONB column.
    assertThat(loaded.shippingAddress()).isEqualTo(ADDRESS);
    // And one flattened into two columns.
    assertThat(loaded.total()).isEqualTo(Money.of("CNY", 2000));
    assertThat(loaded.lines()).hasSize(1);
    // First write: the insert branch set version 1, and the in-memory aggregate was advanced to match.
    assertThat(loaded.version()).isEqualTo(1L);
  }

  @Test
  void anEmptiedColumnActuallyReachesTheDatabase() {
    OrderId id = saveDraftWithNote("order-2", "gift wrap");

    tx.executeWithoutResult(
        status -> {
          Order order = orders.findById(id).orElseThrow();
          order.clearNote();
          orders.save(order);
        });

    // The base class adds an explicit `note = null` for every column the entity's own SET would have
    // dropped. Without it the row would still say "gift wrap" while the version moved and the events
    // published — see theNaiveWriterSilentlyKeepsTheOldValue for that same write done the usual way.
    assertThat(noteOf(id)).isNull();
    assertThat(tx.execute(status -> orders.findById(id).orElseThrow()).note()).isNull();
  }

  @Test
  void theNaiveWriterSilentlyKeepsTheOldValue() {
    OrderId id = saveDraftWithNote("order-3", "gift wrap");

    int updated =
        tx.execute(
            status -> {
              Order order = orders.findById(id).orElseThrow();
              order.clearNote();
              return naive.save(order);
            });

    // The write reported success and the version moved — nothing looks wrong anywhere.
    assertThat(updated).isEqualTo(1);
    assertThat(versionOf(id)).isEqualTo(2L);
    // And the note is still there. This is the failure the repository base class exists to prevent.
    assertThat(noteOf(id)).isEqualTo("gift wrap");
  }

  @Test
  void anAggregateAtVersionZeroTakesTheInsertBranchAndCollides() {
    OrderId id = saveDraftWithNote("order-4", null);

    // Exactly what a rebuild factory that forgot restoreVersion(...) produces: a fresh aggregate,
    // version 0, over an identity that already exists.
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      Order stale = Order.draft(id, "customer-1", ADDRESS);
                      stale.addLine(new LineId("line-x"), "SKU-X", PRICE, 1);
                      orders.save(stale);
                    }))
        .isInstanceOf(DuplicateEntityException.class)
        .hasMessageContaining("forgot to call restoreVersion");
  }

  @Test
  void aStaleAggregateLosesTheRace() {
    OrderId id = saveDraftWithNote("order-5", null);
    Order first = tx.execute(status -> orders.findById(id).orElseThrow());
    Order second = tx.execute(status -> orders.findById(id).orElseThrow());

    tx.executeWithoutResult(
        status -> {
          first.noteFor("won");
          orders.save(first);
        });

    // Same version in the WHERE clause, no row matched. Called through the CommandBus this would have
    // been translated into ConcurrencyConflictException and rendered as 409; called directly, the
    // Spring exception is what surfaces.
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      second.noteFor("lost");
                      orders.save(second);
                    }))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .hasMessageContaining("was modified concurrently");
    assertThat(noteOf(id)).isEqualTo("won");
  }

  @Test
  void savingOutsideATransactionIsRefused() {
    Order order = Order.draft(new OrderId("order-6"), "customer-1", ADDRESS);
    order.addLine(new LineId("line-1"), "SKU-1", PRICE, 1);

    assertThatThrownBy(() -> orders.save(order))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no active transaction while saving aggregate");
  }

  @Test
  void childrenAreDiffedSoUntouchedLinesAreLeftAlone() {
    OrderId id = new OrderId("order-7");
    tx.executeWithoutResult(
        status -> {
          Order order = Order.draft(id, "customer-1", ADDRESS);
          order.addLine(new LineId("line-a"), "SKU-A", PRICE, 1);
          order.addLine(new LineId("line-b"), "SKU-B", PRICE, 5);
          orders.save(order);
        });

    tx.executeWithoutResult(
        status -> {
          Order order = orders.findById(id).orElseThrow();
          order.amendLine(new LineId("line-b"), 9);
          order.removeLine(new LineId("line-a"));
          order.addLine(new LineId("line-c"), "SKU-C", PRICE, 2);
          orders.save(order);
        });

    // line-b kept its identity through an amend, line-a is gone, line-c arrived. A delete-and-reinsert
    // strategy would have produced the same three quantities with a new id for every line.
    assertThat(lineIds(id)).containsExactly("line-b", "line-c");
    assertThat(quantityOf("line-b")).isEqualTo(9);
  }

  @Test
  void aReadThatDoesNotNeedTheAggregateDoesNotBuildOne() {
    OrderId id = saveDraftWithNote("order-8", "note");

    // The write model's own tables, read flat. Nothing about an aggregate is needed to answer this,
    // and rebuilding one to read three columns would buy nothing. Where the shape diverges far enough
    // to need its own store, that is a projection — S12.
    var row =
        jdbc.queryForMap(
            "SELECT customer_id, total_amount_cents FROM s17_order WHERE id = ?", id.value());

    assertThat(row.get("customer_id")).isEqualTo("customer-1");
    assertThat(row.get("total_amount_cents")).isEqualTo(1000L);
  }

  private OrderId saveDraftWithNote(String rawId, String note) {
    OrderId id = new OrderId(rawId);
    tx.executeWithoutResult(
        status -> {
          Order order = Order.draft(id, "customer-1", ADDRESS);
          order.addLine(new LineId(rawId + "-line"), "SKU-1", PRICE, 1);
          if (note != null) {
            order.noteFor(note);
          }
          orders.save(order);
        });
    return id;
  }

  private String noteOf(OrderId id) {
    return jdbc.queryForObject(
        "SELECT note FROM s17_order WHERE id = ?", String.class, id.value());
  }

  private Long versionOf(OrderId id) {
    return jdbc.queryForObject("SELECT version FROM s17_order WHERE id = ?", Long.class, id.value());
  }

  private List<String> lineIds(OrderId id) {
    return jdbc.queryForList(
        "SELECT id FROM s17_order_line WHERE order_id = ? ORDER BY id", String.class, id.value());
  }

  private Integer quantityOf(String lineId) {
    return jdbc.queryForObject(
        "SELECT quantity FROM s17_order_line WHERE id = ?", Integer.class, lineId);
  }
}
