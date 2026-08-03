package com.example.ordering.domain.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.junit.jupiter.api.Test;

class CustomerTest {

  private static final CustomerId ID = new CustomerId("cust-1");

  private static Customer customerWithLimit(long limitMinor) {
    return new Customer(ID, "Ada", Money.of(limitMinor, "USD"));
  }

  /**
   * Construction and rehydration guards: a customer with no id, no limit, or a used balance in
   * another currency is corrupt however it arrives, and a bad row rehydrated without complaint
   * explodes later in reserveCredit — far from the row that caused it.
   */
  @Test
  void rejectsANullIdAndANullCreditLimit() {
    assertThrows(DomainException.class, () -> new Customer(null, "Ada", Money.of(1_000, "USD")));
    assertThrows(DomainException.class, () -> new Customer(ID, "Ada", null));
  }

  @Test
  void rejectsARehydratedBalanceInAnotherCurrency() {
    assertThrows(
        DomainException.class,
        () -> Customer.reconstitute(ID, "Ada", Money.of(1_000, "USD"), Money.of(1, "EUR"), 1L));
  }

  @Test
  void refusesToReserveNull() {
    assertThrows(DomainException.class, () -> customerWithLimit(1_000).reserveCredit(null));
  }

  @Test
  void exposesIdAndName() {
    Customer customer = customerWithLimit(10_000);

    assertSame(ID, customer.id());
    assertEquals("Ada", customer.name());
  }

  @Test
  void creditUpToTheLimitCanBeCommitted() {
    Customer customer = customerWithLimit(10_000);

    customer.reserveCredit(Money.of(10_000, "USD"));

    assertEquals(10_000, customer.usedCredit().amountMinor(), "exactly the limit is committable");
    assertEquals(0, customer.availableCredit().amountMinor());
  }

  @Test
  void creditBeyondTheLimitIsRefused() {
    Customer customer = customerWithLimit(10_000);

    assertThrows(
        CreditExceededException.class, () -> customer.reserveCredit(Money.of(10_001, "USD")));
    assertEquals(0, customer.usedCredit().amountMinor(), "a refusal commits nothing");
  }

  /**
   * The distinction the old {@code canAfford} could not make, and the reason that rule was not
   * really a credit limit: each of these is under the limit on its own, and together they are not.
   * No concurrency is involved — the previous check compared every order against the untouched
   * limit, so it would have allowed both.
   */
  @Test
  void creditAlreadyCommittedCountsAgainstTheLimit() {
    Customer customer = customerWithLimit(10_000);

    customer.reserveCredit(Money.of(6_000, "USD"));

    assertThrows(
        CreditExceededException.class,
        () -> customer.reserveCredit(Money.of(6_000, "USD")),
        "6000 + 6000 exceeds 10000, though neither does alone");
    assertEquals(6_000, customer.usedCredit().amountMinor());
    assertEquals(4_000, customer.availableCredit().amountMinor());
  }

  @Test
  void releasedCreditBecomesAvailableAgain() {
    Customer customer = customerWithLimit(10_000);
    customer.reserveCredit(Money.of(8_000, "USD"));

    customer.releaseCredit(Money.of(8_000, "USD"));

    assertEquals(0, customer.usedCredit().amountMinor());
    // The whole point of releasing: an order that used to be refused now fits.
    customer.reserveCredit(Money.of(10_000, "USD"));
    assertEquals(10_000, customer.usedCredit().amountMinor());
  }

  @Test
  void releasingMoreThanWasCommittedFailsRatherThanInventingHeadroom() {
    Customer customer = customerWithLimit(10_000);
    customer.reserveCredit(Money.of(3_000, "USD"));

    assertThrows(DomainException.class, () -> customer.releaseCredit(Money.of(4_000, "USD")));
  }

  @Test
  void aFreshCustomerHasCommittedNothing() {
    Customer customer = customerWithLimit(10_000);

    assertEquals(0, customer.usedCredit().amountMinor());
    assertEquals(10_000, customer.availableCredit().amountMinor());
    assertEquals(10_000, customer.creditLimit().amountMinor());
  }

  @Test
  void aReconstitutedCustomerCarriesItsCommittedCreditAndVersion() {
    Customer customer =
        Customer.reconstitute(ID, "Ada", Money.of(10_000, "USD"), Money.of(2_500, "USD"), 7L);

    assertEquals(2_500, customer.usedCredit().amountMinor());
    assertEquals(7_500, customer.availableCredit().amountMinor());
    assertFalse(customer.version() == 0, "a stored customer is not a new one");
  }

  @Test
  void customerIdRejectsNullOrBlank() {
    assertThrows(DomainException.class, () -> new CustomerId(null));
    assertThrows(DomainException.class, () -> new CustomerId(" "));
    assertEquals("cust-1", new CustomerId("cust-1").value());
  }

  @Test
  void creditExceededExceptionCarriesTheStableCode() {
    CreditExceededException ex = new CreditExceededException("over limit");

    assertEquals("over limit", ex.getMessage());
    ErrorCode code = ex.errorCode().orElseThrow();
    assertSame(OrderingErrorCode.CREDIT_EXCEEDED, code);
  }
}
