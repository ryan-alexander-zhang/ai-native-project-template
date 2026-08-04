package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s26.catalog.domain.Product;
import com.example.samples.s26.catalog.domain.ProductRenamed;
import com.example.samples.s26.catalog.domain.ProductRepriced;
import com.example.samples.s26.catalog.domain.Sku;
import org.junit.jupiter.api.Test;

/** The aggregate on its own: no database, no cache, no Spring. */
class ProductTest {

  private static final Sku SKU = new Sku("sku-keyboard");

  @Test
  void arenameRecordsWhatChanged() {
    Product product = Product.of(SKU, "Keyboard", 4500);

    assertThat(product.renameTo("Mechanical Keyboard")).isTrue();

    assertThat(product.name()).isEqualTo("Mechanical Keyboard");
    assertThat(product.domainEvents()).containsExactly(new ProductRenamed(SKU, "Mechanical Keyboard"));
  }

  /**
   * A no-op rename records nothing, and that has two consequences worth having in one assertion.
   *
   * <p>No event means nothing to publish, and no event also means <em>no eviction</em> — the cached entry
   * for this product is still correct, so throwing it away would cost a refill for no gain. An idempotent
   * caller resubmitting the same rename therefore does not warm-strip the cache of every product it
   * touches.
   */
  @Test
  void arenameToTheSameNameChangesNothingAndSaysNothing() {
    Product product = Product.of(SKU, "Keyboard", 4500);

    assertThat(product.renameTo("Keyboard")).isFalse();
    assertThat(product.domainEvents()).isEmpty();
  }

  @Test
  void arepriceRecordsWhatChanged() {
    Product product = Product.of(SKU, "Keyboard", 4500);

    assertThat(product.repriceTo(3900)).isTrue();

    assertThat(product.priceCents()).isEqualTo(3900);
    assertThat(product.domainEvents()).containsExactly(new ProductRepriced(SKU, 3900));
  }

  @Test
  void arepriceToTheSamePriceChangesNothing() {
    Product product = Product.of(SKU, "Keyboard", 4500);

    assertThat(product.repriceTo(4500)).isFalse();
    assertThat(product.domainEvents()).isEmpty();
  }

  @Test
  void anameIsRequired() {
    assertThatThrownBy(() -> Product.of(SKU, "  ", 4500))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  @Test
  void apriceIsPositive() {
    assertThatThrownBy(() -> Product.of(SKU, "Keyboard", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("price");
  }

  /** Whitespace is stripped on the way in, so the cached copy and the row cannot differ by a space. */
  @Test
  void anameIsStripped() {
    Product product = Product.of(SKU, "  Keyboard  ", 4500);

    assertThat(product.name()).isEqualTo("Keyboard");
  }
}
