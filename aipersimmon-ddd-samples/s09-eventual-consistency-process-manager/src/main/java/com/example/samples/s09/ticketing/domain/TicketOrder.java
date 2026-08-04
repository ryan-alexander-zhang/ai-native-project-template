package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * A customer's ticket order — and, for this sample, <strong>the holder of the truth</strong>.
 *
 * <p>The catalogue's hardest question about a process manager is whether the flow's step and the
 * aggregate's status are two copies of one fact. Here they are not, and the division is deliberate:
 *
 * <ul>
 *   <li>this aggregate answers <em>what is true of the order</em> — placed, ticketed, cancelled. It is
 *       what a client is shown, what a report counts, and what another context would be told about.
 *   <li>the flow's step answers <em>what the coordinator is waiting for</em> — a fact about the
 *       coordination, not about the order.
 *   </ul>
 *
 * <p>They therefore disagree by design during every step: the flow can be at {@code AWAITING_TICKET}
 * while this order is still {@code PLACED}, because the flow has dispatched the command and the command
 * has not committed yet. A test asserts exactly that window and calls it correct. What would be a defect
 * is the flow storing a copy of {@code status} — then there would be two answers to one question and
 * nothing to reconcile them.
 *
 * <p>Both transitions are idempotent by return value rather than by exception, because the coordinator
 * delivers its commands at-least-once: a redelivered {@code IssueTicket} must be a no-op, not a failure
 * that poisons the effect relay. The one case that <em>is</em> an exception is the point of no return.
 */
@AggregateRoot
public final class TicketOrder extends AbstractAggregateRoot<TicketOrderId> {

  private final TicketOrderId id;
  private final String customerId;
  private final String seatClass;
  private final long amountMinor;

  private OrderStatus status;
  private String cancelReason;

  private TicketOrder(
      TicketOrderId id,
      String customerId,
      String seatClass,
      long amountMinor,
      OrderStatus status,
      String cancelReason) {
    this.id = id;
    this.customerId = customerId;
    this.seatClass = seatClass;
    this.amountMinor = amountMinor;
    this.status = status;
    this.cancelReason = cancelReason;
  }

  public static TicketOrder place(
      TicketOrderId id, String customerId, String seatClass, long amountMinor) {
    TicketOrder order =
        new TicketOrder(id, customerId, seatClass, amountMinor, OrderStatus.PLACED, null);
    order.checkInvariant(new AmountIsPositive(amountMinor));
    return order;
  }

  public static TicketOrder reconstitute(
      TicketOrderId id,
      String customerId,
      String seatClass,
      long amountMinor,
      OrderStatus status,
      String cancelReason,
      long version) {
    TicketOrder order =
        new TicketOrder(id, customerId, seatClass, amountMinor, status, cancelReason);
    order.restoreVersion(version);
    return order;
  }

  /**
   * The flow's last forward step, and the point of no return.
   *
   * @return false when this order is already ticketed — a redelivered effect, absorbed
   * @throws DomainException if the order was cancelled: a cancelled order must not become a ticket,
   *     and the coordinator reaching this step for a cancelled order is a defect in the flow rather
   *     than a business case, so it is loud
   */
  public boolean issueTicket() {
    if (status == OrderStatus.TICKETED) {
      return false;
    }
    if (status == OrderStatus.CANCELLED) {
      throw new DomainException(
          TicketingErrorCode.ORDER_ALREADY_CANCELLED, "a cancelled order cannot be ticketed");
    }
    status = OrderStatus.TICKETED;
    return true;
  }

  /**
   * The flow's compensating terminal step.
   *
   * @return false when the order is already cancelled — a redelivered effect, absorbed
   * @throws DomainException if the ticket was issued. This is the assertion that a saga has a point of
   *     no return: undoing a ticket is not a compensation, it is a refund flow with its own rules, its
   *     own authorisation and its own money movement. Pretending otherwise is how a "generic rollback"
   *     ends up cancelling something a customer is holding in their hand.
   */
  public boolean cancel(String reason) {
    if (status == OrderStatus.CANCELLED) {
      return false;
    }
    if (status == OrderStatus.TICKETED) {
      throw new DomainException(
          TicketingErrorCode.TICKET_ALREADY_ISSUED,
          "a ticketed order cannot be cancelled by the fulfilment flow");
    }
    status = OrderStatus.CANCELLED;
    cancelReason = reason;
    return true;
  }

  @Override
  public TicketOrderId id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public String seatClass() {
    return seatClass;
  }

  public long amountMinor() {
    return amountMinor;
  }

  public OrderStatus status() {
    return status;
  }

  public String cancelReason() {
    return cancelReason;
  }
}
