package com.example.ordering.domain.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  void exposesIdAndName() {
    Customer customer = customerWithLimit(10_000);

    assertSame(ID, customer.id());
    assertEquals("Ada", customer.name());
  }

  @Test
  void canAffordAtOrBelowTheCreditLimit() {
    Customer customer = customerWithLimit(10_000);

    assertTrue(customer.canAfford(Money.of(10_000, "USD")), "exactly the limit is affordable");
    assertTrue(customer.canAfford(Money.of(9_999, "USD")));
  }

  @Test
  void cannotAffordAboveTheCreditLimit() {
    Customer customer = customerWithLimit(10_000);

    assertFalse(customer.canAfford(Money.of(10_001, "USD")));
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
