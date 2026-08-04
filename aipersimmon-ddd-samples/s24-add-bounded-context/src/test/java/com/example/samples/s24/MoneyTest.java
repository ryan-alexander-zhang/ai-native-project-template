package com.example.samples.s24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s24.sharedkernel.api.Money;
import org.junit.jupiter.api.Test;

/**
 * The shared kernel's arithmetic, tested at the cheapest layer — and tested at all, which is the point.
 *
 * <p>A shared kernel is the code with the most callers and the least ownership, which is exactly the combination that ends
 * up untested. Rounding is the part that matters: a percentage that rounded up would invent money, once per order, in a
 * type three contexts share.
 */
class MoneyTest {

  @Test
  void apercentageRoundsDownSoItNeverInventsMoney() {
    assertThat(Money.of(999, "GBP").percent(10)).isEqualTo(Money.of(99, "GBP"));
    assertThat(Money.of(1, "GBP").percent(50)).isEqualTo(Money.of(0, "GBP"));
  }

  @Test
  void currenciesDoNotMix() {
    assertThatThrownBy(() -> Money.of(100, "GBP").plus(Money.of(100, "EUR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot combine GBP with EUR");
  }

  @Test
  void acurrencyIsThreeLetters() {
    assertThatThrownBy(() -> Money.of(100, "POUNDS"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("three-letter code");
  }

  @Test
  void moneyAddsUpAndTakesAway() {
    assertThat(Money.of(300, "GBP").plus(Money.of(200, "GBP"))).isEqualTo(Money.of(500, "GBP"));
    assertThat(Money.of(300, "GBP").minus(Money.of(200, "GBP"))).isEqualTo(Money.of(100, "GBP"));
    assertThat(Money.of(300, "GBP").times(3)).isEqualTo(Money.of(900, "GBP"));
    assertThat(Money.of(-1, "GBP").isNegative()).isTrue();
  }
}
