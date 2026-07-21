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
  /** The supplied review decision belongs to a different order. */
  REVIEW_DECISION_ORDER_MISMATCH(
      "ordering.review-decision-order-mismatch", ErrorCategory.DOMAIN_RULE),
  /** A shipped order cannot be cancelled; it must enter the return flow instead. */
  RETURN_REQUIRED("ordering.return-required", ErrorCategory.CONFLICT);

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
