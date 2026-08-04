package com.example.samples.s27;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s27.customer.domain.Customer;
import com.example.samples.s27.customer.domain.CustomerId;
import com.example.samples.s27.customer.domain.CustomerStatus;
import com.example.samples.s27.customer.domain.EmailAddress;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The aggregate on its own: no database, no Spring. */
class CustomerTest {

  private static final CustomerId ID = new CustomerId("cust-alice");
  private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

  private static Customer alice() {
    return Customer.register(ID, new EmailAddress("alice@example.com"), "Alice", "+44 7700 900000");
  }

  @Test
  void aclosureCarriesItsReasonAndIsReversible() {
    Customer customer = alice();

    assertThat(customer.close("moved on")).isTrue();
    assertThat(customer.status()).isEqualTo(CustomerStatus.CLOSED);
    assertThat(customer.closedReason()).contains("moved on");

    assertThat(customer.reopen()).isTrue();
    assertThat(customer.status()).isEqualTo(CustomerStatus.ACTIVE);
    assertThat(customer.closedReason()).isEmpty();
  }

  @Test
  void closingTwiceChangesNothing() {
    Customer customer = alice();
    customer.close("moved on");

    assertThat(customer.close("a different reason")).isFalse();
    assertThat(customer.closedReason()).contains("moved on");
  }

  /** The tombstones are values the model accepts, which is what keeps the erasure inside the domain. */
  @Test
  void anerasureOverwritesEverythingPersonal() {
    Customer customer = alice();

    assertThat(customer.erase(NOW)).isTrue();

    assertThat(customer.email().value()).isEqualTo("erased+cust-alice@invalid");
    assertThat(customer.displayName()).isEqualTo("(erased)");
    assertThat(customer.phone()).isEmpty();
    assertThat(customer.status()).isEqualTo(CustomerStatus.CLOSED);
    assertThat(customer.erasedAt()).contains(NOW);
    assertThat(customer.isErased()).isTrue();
  }

  /** Idempotent, and it keeps the original date, because the date is evidence. */
  @Test
  void asecondErasureIsAnoOpAndKeepsTheDate() {
    Customer customer = alice();
    customer.erase(NOW);

    assertThat(customer.erase(NOW.plusSeconds(3600))).isFalse();
    assertThat(customer.erasedAt()).contains(NOW);
  }

  /**
   * And nothing else can be done to an erased profile.
   *
   * <p>Three refusals rather than one, because each is a different way somebody arrives here: a support agent
   * changing an address, a batch reopening dormant accounts, a replayed message. The message says what to do about
   * it — nothing.
   */
  @Test
  void anerasedProfileRefusesEveryOtherChange() {
    Customer customer = alice();
    customer.erase(NOW);

    assertThatThrownBy(() -> customer.changeEmailTo(new EmailAddress("new@example.com")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("was erased at");
    assertThatThrownBy(() -> customer.close("late"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(customer::reopen).isInstanceOf(IllegalStateException.class);
  }

  /** A no-op email change announces nothing, so an idempotent retry does not broadcast. */
  @Test
  void changingToTheSameAddressChangesNothing() {
    Customer customer = alice();

    assertThat(customer.changeEmailTo(new EmailAddress("alice@example.com"))).isFalse();
  }

  /** The tombstone address has to be constructible, which is why the value object is loose. */
  @Test
  void thetombstoneIsAValidEmailAddress() {
    assertThat(new EmailAddress("erased+cust-alice@invalid").value())
        .isEqualTo("erased+cust-alice@invalid");
  }

  @Test
  void arealAddressStillHasToLookLikeOne() {
    assertThatThrownBy(() -> new EmailAddress("not-an-address"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailAddress("@example.com"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailAddress("alice@"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
