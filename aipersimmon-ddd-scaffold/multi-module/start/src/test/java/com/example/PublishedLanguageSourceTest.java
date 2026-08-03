package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Every published contract names its own producing context (CloudEvents {@code source}) — this
 * application hosts three bounded contexts, so the deployment-wide fallback has no single true
 * answer, and before these declarations existed every event on the wire claimed {@code ordering} as
 * its producer. Pinned per contract because consumers dedup on {@code (source, id)}: silently
 * losing a declaration would change dedup identity, not just a label.
 */
class PublishedLanguageSourceTest {

  private static final List<Class<? extends IntegrationEvent>> ORDERING =
      List.of(
          com.example.ordering.api.OrderReadyForFulfilment.class,
          com.example.ordering.api.OrderReadyForFulfilmentV1.class,
          com.example.ordering.api.PaymentRequested.class,
          com.example.ordering.api.StockReleaseRequested.class);

  private static final List<Class<? extends IntegrationEvent>> INVENTORY =
      List.of(
          com.example.inventory.api.StockReserved.class,
          com.example.inventory.api.StockReleased.class,
          com.example.inventory.api.StockReservationFailed.class);

  private static final List<Class<? extends IntegrationEvent>> PAYMENT =
      List.of(
          com.example.payment.api.PaymentAuthorized.class,
          com.example.payment.api.PaymentDeclined.class);

  @Test
  void everyContractDeclaresItsOwnContextAsSource() {
    ORDERING.forEach(type -> assertDeclaredSource(type, "/ordering"));
    INVENTORY.forEach(type -> assertDeclaredSource(type, "/inventory"));
    PAYMENT.forEach(type -> assertDeclaredSource(type, "/payment"));
  }

  private static void assertDeclaredSource(Class<? extends IntegrationEvent> type, String source) {
    Optional<String> declared = IntegrationEvent.sourceOf(type);
    assertTrue(declared.isPresent(), type.getSimpleName() + " must declare its producing context");
    assertEquals(source, declared.orElseThrow(), type.getSimpleName());
  }
}
