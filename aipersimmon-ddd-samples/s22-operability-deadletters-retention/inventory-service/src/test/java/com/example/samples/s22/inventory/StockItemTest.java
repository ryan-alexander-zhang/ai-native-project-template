package com.example.samples.s22.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.samples.s22.inventory.domain.Sku;
import com.example.samples.s22.inventory.domain.StockItem;
import org.junit.jupiter.api.Test;

/**
 * The rule, at the cheapest layer that can answer for it (S18).
 *
 * <p>It belongs in an operability sample for one reason: this refusal is the failure the error handler
 * classifies as neither poison nor outage, and it is the reason the "everything else" tier exists. A
 * record that asks for more than there is has been understood perfectly — retrying it will refuse
 * identically, and the bounded-retry-then-DLT path is the only honest place for it.
 */
class StockItemTest {

  @Test
  void areservationTakesFromAvailableAndAddsToReserved() {
    StockItem item = StockItem.reconstitute(new Sku("sku-keyboard"), 10, 0, 1);

    item.reserve(3);

    assertThat(item.available()).isEqualTo(7);
    assertThat(item.reserved()).isEqualTo(3);
  }

  @Test
  void youcannotReserveWhatIsNotThere() {
    StockItem item = StockItem.reconstitute(new Sku("sku-keyboard"), 2, 0, 1);

    assertThatThrownBy(() -> item.reserve(3)).isInstanceOf(DomainException.class);
  }

  @Test
  void arefusalLeavesTheAggregateUntouched() {
    StockItem item = StockItem.reconstitute(new Sku("sku-keyboard"), 2, 0, 1);

    assertThatThrownBy(() -> item.reserve(3)).isInstanceOf(DomainException.class);

    assertThat(item.available()).isEqualTo(2);
    assertThat(item.reserved()).isZero();
  }
}
