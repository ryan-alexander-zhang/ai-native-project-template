package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * The inventory context's catalogue of stable, machine-readable error codes. Same pure {@link
 * ErrorCode} pattern as ordering's {@code OrderingErrorCode} (transport-neutral, per-BC enum) — but
 * note inventory has <strong>no inbound HTTP surface</strong>: it reacts to an integration event
 * and reports failure as a {@code StockReservationFailed} event, never an RFC 9457 response. So
 * there is deliberately <em>no</em> {@code ProblemCatalog}/{@code ProblemDescriptor} here (those
 * are the HTTP boundary's concern); instead the code travels on the failure event, giving the
 * reacting process manager a stable identity to branch on. This is the event-driven counterpart to
 * ordering's HTTP-facing use of the same {@code ErrorCode} model (design-00003 §4.5/§4.7).
 */
public enum InventoryErrorCode implements ErrorCode {

  /**
   * A reservation asked for more than is available. Exactly that, and not "or a non-positive
   * quantity", which this doc used to claim while the code never did: a non-positive quantity is a
   * malformed request the bus's validation refuses long before the domain — {@code Stock.reserve}'s
   * own guard for it is a codeless backstop for callers that bypass the bus, and a codeless
   * DomainException deliberately surfaces as {@code inventory.unspecified} (issue-00131).
   */
  INSUFFICIENT_STOCK("inventory.insufficient-stock", ErrorCategory.DOMAIN_RULE),

  /** No stock record exists for the requested SKU. */
  STOCK_NOT_FOUND("inventory.stock-not-found", ErrorCategory.NOT_FOUND),

  /** No reservation exists for the id a release referred to. */
  RESERVATION_NOT_FOUND("inventory.reservation-not-found", ErrorCategory.NOT_FOUND),

  /**
   * A domain refusal that carried no code of its own. This is the floor under the published
   * contract: {@code StockReservationFailed.code} promises a stable machine identity and the
   * consuming side enforces that promise ({@code ReservationFailureRef} refuses a null code), so a
   * codeless {@code DomainException} must leave this context wearing <em>something</em> stable
   * rather than poisoning the consumer's transaction (issue-00131).
   */
  UNSPECIFIED("inventory.unspecified", ErrorCategory.DOMAIN_RULE);

  private final String code;
  private final ErrorCategory category;

  InventoryErrorCode(String code, ErrorCategory category) {
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
