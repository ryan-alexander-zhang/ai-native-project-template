package com.example.ordering.domain.shared;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * The ordering context's catalogue of stable, machine-readable error codes. Domain and application
 * code carries one of these on the exceptions it throws, so the code is fixed where the error
 * originates and travels unchanged to the API edge. It is a pure {@link ErrorCode} (no HTTP or
 * transport concern); the interface layer resolves each code to a wire {@code ProblemDescriptor}
 * (its category family, or a per-code override in {@code OrderingProblemCatalog}).
 */
public enum OrderingErrorCode implements ErrorCode {
  CREDIT_EXCEEDED("ordering.credit-exceeded", ErrorCategory.DOMAIN_RULE),
  ORDER_EMPTY("ordering.order-empty", ErrorCategory.DOMAIN_RULE),
  TOO_MANY_LINES("ordering.too-many-lines", ErrorCategory.DOMAIN_RULE),
  DUPLICATE_SKU("ordering.duplicate-sku", ErrorCategory.DOMAIN_RULE),
  /**
   * An order's lines price themselves in more than one currency. Previously enforced only as an
   * arithmetic side effect ({@code total()} tripping Money's same-currency check, codeless); a rule
   * the aggregate relies on carries its own name and code.
   */
  MIXED_CURRENCY("ordering.mixed-currency", ErrorCategory.DOMAIN_RULE),
  /**
   * A monetary amount, or a quantity feeding one, is too large to represent. Coded rather than a
   * bare DomainException so the refusal survives to the API edge as something a client can branch
   * on instead of landing in the about:blank family.
   */
  AMOUNT_OVERFLOW("ordering.amount-overflow", ErrorCategory.DOMAIN_RULE),
  QUANTITY_OUT_OF_RANGE("ordering.quantity-out-of-range", ErrorCategory.DOMAIN_RULE),
  /**
   * An ordered SKU cannot currently be offered by the inventory context (unknown or out of stock).
   */
  STOCK_UNAVAILABLE("ordering.stock-unavailable", ErrorCategory.DOMAIN_RULE),
  ORDER_NOT_FOUND("ordering.order-not-found", ErrorCategory.NOT_FOUND),
  CUSTOMER_NOT_FOUND("ordering.customer-not-found", ErrorCategory.NOT_FOUND),

  // --- Order-lifecycle / cancellation rules (see OrderLifecyclePolicy) ---
  /** The caller attempting a customer cancellation is not the order's own customer. */
  NOT_ORDER_CUSTOMER("ordering.not-order-customer", ErrorCategory.FORBIDDEN),
  /** The customer's self-cancellation window has closed (fulfilment has started). */
  CUSTOMER_CANCELLATION_WINDOW_CLOSED(
      "ordering.customer-cancellation-window-closed", ErrorCategory.CONFLICT),
  /** An inventory-failure cancellation was attempted on an order that is not under fulfilment. */
  INVENTORY_FAILURE_NOT_APPLICABLE(
      "ordering.inventory-failure-not-applicable", ErrorCategory.CONFLICT),
  /** The supplied reservation-failure evidence belongs to a different order. */
  RESERVATION_FAILURE_ORDER_MISMATCH(
      "ordering.reservation-failure-order-mismatch", ErrorCategory.DOMAIN_RULE),
  /** A payment-failure cancellation was attempted on an order that is not under fulfilment. */
  PAYMENT_FAILURE_NOT_APPLICABLE("ordering.payment-failure-not-applicable", ErrorCategory.CONFLICT),
  /** The supplied compensation evidence (decline and/or release) belongs to a different order. */
  COMPENSATION_EVIDENCE_ORDER_MISMATCH(
      "ordering.compensation-evidence-order-mismatch", ErrorCategory.DOMAIN_RULE),
  /** A review-related action was attempted on an order that is not awaiting review. */
  ORDER_NOT_AWAITING_REVIEW("ordering.order-not-awaiting-review", ErrorCategory.CONFLICT),

  // --- Mechanical forward transitions, named per destination in Order's transition table
  // . One code per destination: the refusal is about where the caller tried to go.
  /** Fulfilment was begun on an order that is not ready for fulfilment. */
  ORDER_NOT_READY_FOR_FULFILMENT("ordering.order-not-ready-for-fulfilment", ErrorCategory.CONFLICT),
  /** A confirmation was attempted on an order that is not under fulfilment. */
  ORDER_NOT_UNDER_FULFILMENT("ordering.order-not-under-fulfilment", ErrorCategory.CONFLICT),
  /** A shipment was attempted on an order that is not confirmed. */
  ORDER_NOT_CONFIRMED("ordering.order-not-confirmed", ErrorCategory.CONFLICT),
  /** The supplied review decision belongs to a different order. */
  REVIEW_DECISION_ORDER_MISMATCH(
      "ordering.review-decision-order-mismatch", ErrorCategory.DOMAIN_RULE),
  /** A shipped order cannot be cancelled; it must enter the return flow instead. */
  RETURN_REQUIRED("ordering.return-required", ErrorCategory.CONFLICT),
  /**
   * The order is already cancelled, whatever the new reason. Its own code because every
   * reason-specific refusal misstates this situation: a retrying customer would be told the order
   * "entered fulfilment", a redelivered compensation that its failure "is not applicable" — both
   * false, and both harder to act on than the actual fact.
   */
  ALREADY_CANCELLED("ordering.already-cancelled", ErrorCategory.CONFLICT);

  private final String code;
  private final ErrorCategory category;

  OrderingErrorCode(String code, ErrorCategory category) {
    this.code = code;
    this.category = category;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public ErrorCategory category() {
    return category;
  }
}
