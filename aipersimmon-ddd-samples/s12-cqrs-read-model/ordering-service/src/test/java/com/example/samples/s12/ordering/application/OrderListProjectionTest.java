package com.example.samples.s12.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a projection row contains, decided with no database and no Spring.
 *
 * <p>This is the payoff of keeping the row's definition in the application layer behind three ports. Every
 * one of these cases is a rule about the read model — the fallback for an unknown product, the deduplication
 * of repeated skus, the timestamp — and none of them needs PostgreSQL to state or to check. Had the same
 * logic been written as SQL inside the adapter, every one of these would be an integration test.
 */
class OrderListProjectionTest {

  private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");
  private static final Instant PLACED = Instant.parse("2026-08-04T09:00:00Z");

  private final FakeOrderFacts facts = new FakeOrderFacts();
  private final FakeProductNames names = new FakeProductNames();
  private final FakeWriter writer = new FakeWriter();

  // This test lives in the production class's own package, so the package-private constructor is simply
  // reachable. That is the whole reason the projection takes its three ports as constructor arguments: the
  // unit under test is assembled here, in two lines, with no container and no reflection.
  private final OrderListProjection projection =
      new OrderListProjection(facts, names, writer, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void arowCarriesTheCurrentNamesAndTheMomentItWasComputed() {
    names.put("sku-keyboard", "Mechanical Keyboard");
    names.put("sku-mouse", "Wireless Mouse");
    facts.put(fact("order-1", "PLACED", null, List.of("sku-keyboard", "sku-mouse")));

    projection.rebuild("order-1");

    OrderListItem row = writer.only();
    assertThat(row.displaySummary()).isEqualTo("Mechanical Keyboard, Wireless Mouse");
    assertThat(row.lineCount()).isEqualTo(2);
    assertThat(row.projectedAt()).isEqualTo(NOW);
  }

  @Test
  void aproductThisContextHasNotHeardOfShowsAsItsSku() {
    names.put("sku-keyboard", "Mechanical Keyboard");
    facts.put(fact("order-2", "PLACED", null, List.of("sku-keyboard", "sku-monitor")));

    projection.rebuild("order-2");

    // Not blank, not "unknown", not an exception. A stable, recognisable placeholder that corrects itself
    // when the catalogue's event arrives — because refusing to project would let another context's silence
    // stop this one from showing orders at all.
    assertThat(writer.only().displaySummary()).isEqualTo("Mechanical Keyboard, sku-monitor");
  }

  @Test
  void twolinesOfTheSameProductNameItOnce() {
    names.put("sku-keyboard", "Mechanical Keyboard");
    facts.put(fact("order-3", "PLACED", null, List.of("sku-keyboard", "sku-keyboard")));

    projection.rebuild("order-3");

    // The summary is for a human, so it deduplicates; the line count is a count, so it does not.
    assertThat(writer.only().displaySummary()).isEqualTo("Mechanical Keyboard");
    assertThat(writer.only().lineCount()).isEqualTo(2);
  }

  @Test
  void rebuildingTwiceProducesTheSameRow() {
    names.put("sku-keyboard", "Mechanical Keyboard");
    facts.put(fact("order-4", "PLACED", null, List.of("sku-keyboard")));

    projection.rebuild("order-4");
    OrderListItem first = writer.only();
    projection.rebuild("order-4");

    // The property that makes a redelivered event harmless and a rebuild safe, stated at the cheapest layer
    // that can state it.
    assertThat(writer.saved).hasSize(2);
    assertThat(writer.saved.get(1)).isEqualTo(first);
  }

  @Test
  void apaidOrderCarriesItsPaymentTime() {
    Instant paidAt = Instant.parse("2026-08-04T09:30:00Z");
    names.put("sku-keyboard", "Mechanical Keyboard");
    facts.put(fact("order-5", "PAID", paidAt, List.of("sku-keyboard")));

    projection.rebuild("order-5");

    assertThat(writer.only().status()).isEqualTo("PAID");
    assertThat(writer.only().paidAt()).isEqualTo(paidAt);
  }

  @Test
  void anorderThatIsNotThereIsSilenceRatherThanAFailure() {
    projection.rebuild("order-that-never-existed");

    assertThat(writer.saved).isEmpty();
  }

  private static OrderFacts.OrderFact fact(
      String orderId, String status, Instant paidAt, List<String> skus) {
    return new OrderFacts.OrderFact(orderId, "customer-1", status, PLACED, paidAt, 4500, skus);
  }

  // --- fakes ---------------------------------------------------------------------------------------

  private static final class FakeOrderFacts implements OrderFacts {
    private final Map<String, OrderFact> byId = new LinkedHashMap<>();

    void put(OrderFact fact) {
      byId.put(fact.orderId(), fact);
    }

    @Override
    public Optional<OrderFact> find(String orderId) {
      return Optional.ofNullable(byId.get(orderId));
    }

    @Override
    public List<String> orderIdsContaining(String sku) {
      return byId.values().stream()
          .filter(fact -> fact.skusInOrder().contains(sku))
          .map(OrderFact::orderId)
          .toList();
    }

    @Override
    public List<String> allOrderIds() {
      return List.copyOf(byId.keySet());
    }
  }

  private static final class FakeProductNames implements ProductNames {
    private final Map<String, String> names = new LinkedHashMap<>();

    void put(String sku, String name) {
      names.put(sku, name);
    }

    @Override
    public Map<String, String> namesOf(List<String> skus) {
      Map<String, String> found = new LinkedHashMap<>();
      skus.forEach(
          sku -> {
            if (names.containsKey(sku)) {
              found.put(sku, names.get(sku));
            }
          });
      return found;
    }

    @Override
    public Optional<String> nameOf(String sku) {
      return Optional.ofNullable(names.get(sku));
    }

    @Override
    public void record(String sku, String name, Instant at) {
      names.put(sku, name);
    }
  }

  private static final class FakeWriter implements OrderListWriter {
    private final List<OrderListItem> saved = new ArrayList<>();

    @Override
    public void save(OrderListItem item) {
      saved.add(item);
    }

    @Override
    public void deleteAll() {
      saved.clear();
    }

    OrderListItem only() {
      return saved.get(saved.size() - 1);
    }
  }
}
