package com.example.samples.s08;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.rule.InvariantViolationException;
import com.example.samples.s08.inventory.application.ReserveStock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Who opens the transaction, and what all-or-nothing actually means across two aggregates. */
class TransactionBoundaryTest extends InventoryTestBase {

  @Test
  void aReservationAcrossTwoSkusCommitsAsOne() {
    commandBus.send(reserve("SKU-A", 10, "SKU-B", 4));

    assertThat(availableOf("SKU-A")).isEqualTo(90);
    assertThat(availableOf("SKU-B")).isEqualTo(96);
  }

  @Test
  void whenTheSecondSkuIsRefusedTheFirstIsNotWrittenEither() {
    assertThatThrownBy(() -> commandBus.send(reserve("SKU-A", 10, "SKU-B", 500)))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("SKU-B");

    // No @Transactional anywhere in the handler: the bus's transaction interceptor opened one around
    // the whole dispatch, so the first sku's write went back with the second sku's refusal.
    assertThat(availableOf("SKU-A")).isEqualTo(100);
    assertThat(availableOf("SKU-B")).isEqualTo(100);
    assertThat(versionOf("SKU-A")).isEqualTo(1L);
  }

  @Test
  void anUnknownSkuIsRefusedBeforeAnythingIsWritten() {
    assertThatThrownBy(() -> commandBus.send(reserve("SKU-A", 10, "SKU-NOPE", 1)))
        .hasMessageContaining("SKU-NOPE");

    assertThat(availableOf("SKU-A")).isEqualTo(100);
  }

  @Test
  void twoLinesNamingOneSkuAreOneReservation() {
    commandBus.send(reserve("SKU-A", 10, "SKU-A", 15));

    // Merged before loading. Two separate loads of one aggregate inside one command would have the
    // second overwrite the first's decision, and the version would not notice: same row, same
    // transaction, same expected version.
    assertThat(availableOf("SKU-A")).isEqualTo(75);
    assertThat(versionOf("SKU-A")).isEqualTo(2L);
  }

  private static ReserveStock reserve(String sku1, int qty1, String sku2, int qty2) {
    return new ReserveStock(
        List.of(new ReserveStock.Line(sku1, qty1), new ReserveStock.Line(sku2, qty2)));
  }
}
