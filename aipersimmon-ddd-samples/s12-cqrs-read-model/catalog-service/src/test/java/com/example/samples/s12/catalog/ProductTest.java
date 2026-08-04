package com.example.samples.s12.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s12.catalog.domain.Product;
import com.example.samples.s12.catalog.domain.Sku;
import org.junit.jupiter.api.Test;

/** The catalogue's one rule, and it is about not being noisy. */
class ProductTest {

  private static Product keyboard() {
    return Product.of(new Sku("sku-keyboard"), "Mechanical Keyboard");
  }

  @Test
  void arenameToADifferentNameChangesIt() {
    Product product = keyboard();

    assertThat(product.renameTo("Keyboard Pro")).isTrue();
    assertThat(product.name()).isEqualTo("Keyboard Pro");
  }

  @Test
  void arenameToTheSameNameIsNotARename() {
    Product product = keyboard();

    // Returning false is what stops an idempotent retry upstream from becoming a second broadcast that every
    // consumer has to absorb — and on the other side of the wire, from recomputing every list row containing
    // this product for nothing.
    assertThat(product.renameTo("Mechanical Keyboard")).isFalse();
  }

  @Test
  void surroundingWhitespaceIsNotAName() {
    Product product = keyboard();

    assertThat(product.renameTo("  Mechanical Keyboard  ")).isFalse();
  }

  @Test
  void ablankNameIsRefused() {
    assertThatThrownBy(() -> keyboard().renameTo(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
