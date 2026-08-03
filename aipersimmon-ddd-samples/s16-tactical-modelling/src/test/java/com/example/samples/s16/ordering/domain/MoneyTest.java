package com.example.samples.s16.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Value-object semantics: equality by attributes, immutability, and no identity anywhere. */
class MoneyTest {

  @Test
  void equalWhenTheAttributesAreEqual() {
    assertThat(Money.of("CNY", "10.00")).isEqualTo(Money.of("CNY", "10.00"));
    assertThat(Money.of("CNY", "10.00")).isNotEqualTo(Money.of("USD", "10.00"));
  }

  @Test
  void scaleIsNormalisedSoTenEqualsTenPointZero() {
    // Without the setScale in the constructor these would differ: a record's equality delegates to
    // BigDecimal.equals, which compares scale as well as value.
    assertThat(Money.of("CNY", "10")).isEqualTo(Money.of("CNY", "10.00"));
    assertThat(Money.of("CNY", "10").hashCode()).isEqualTo(Money.of("CNY", "10.00").hashCode());
  }

  @Test
  void arithmeticAnswersANewValueAndLeavesTheOldOneAlone() {
    Money ten = Money.of("CNY", "10.00");

    assertThat(ten.plus(Money.of("CNY", "5.50"))).isEqualTo(Money.of("CNY", "15.50"));
    assertThat(ten.times(3)).isEqualTo(Money.of("CNY", "30.00"));
    assertThat(ten.percent(10)).isEqualTo(Money.of("CNY", "1.00"));
    assertThat(ten).isEqualTo(Money.of("CNY", "10.00"));
  }

  @Test
  void mixingCurrenciesIsRefused() {
    assertThatThrownBy(() -> Money.of("CNY", "1.00").plus(Money.of("USD", "1.00")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot combine CNY with USD");
  }

  @Test
  void malformedMoneyIsRefusedAtConstruction() {
    assertThatThrownBy(() -> new Money("CN", BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Money("CNY", new BigDecimal("-1")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
