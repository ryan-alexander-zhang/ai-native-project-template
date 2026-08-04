package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s26.catalog.domain.Products;
import com.example.samples.s26.catalog.domain.Sku;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The sibling control for {@link AggregateCacheTrapTest}: the identical sequence, without the memoisation.
 *
 * <p>Without this class the trap test proves nothing. "The rename did not happen" is only a finding if the same
 * rename, in the same state, against the same schema, does happen when the one thing under test is removed.
 */
class AggregateWriteWithoutTheTrapTest extends CacheTestBase {

  @Autowired private Products products;

  /** The same setup, and the rename lands. */
  @Test
  void thesameSequenceWritesWhatItWasAskedTo() {
    products.find(new Sku(KEYBOARD));

    jdbc.update(
        "UPDATE s26_product SET name = 'Moved On', version = version + 1 WHERE sku = ?", KEYBOARD);

    rename(KEYBOARD, "Mechanical Keyboard");

    assertThat(storedName(KEYBOARD)).isEqualTo("Mechanical Keyboard");
  }

  /**
   * And a read always reflects the row, because there is nothing between the two.
   *
   * <p>The other half of the control: an in-memory mutation that was never saved is gone the moment the
   * aggregate is loaded again, which is the property the memoising decorator removes.
   */
  @Test
  void anunsavedChangeIsGoneOnTheNextLoad() {
    products.find(new Sku(KEYBOARD)).orElseThrow().renameTo("Half Done");

    assertThat(products.find(new Sku(KEYBOARD)).orElseThrow().name()).isEqualTo("Keyboard");
    assertThat(storedName(KEYBOARD)).isEqualTo("Keyboard");
  }

  /** The version is the row's. */
  @Test
  void theversionAlwaysComesFromTheRow() {
    products.find(new Sku(KEYBOARD));

    jdbc.update("UPDATE s26_product SET version = version + 1 WHERE sku = ?", KEYBOARD);

    assertThat(products.find(new Sku(KEYBOARD)).orElseThrow().version()).isEqualTo(2);
  }
}
