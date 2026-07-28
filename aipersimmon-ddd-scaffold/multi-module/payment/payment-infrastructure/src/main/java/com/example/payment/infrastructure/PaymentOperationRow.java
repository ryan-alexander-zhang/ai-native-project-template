package com.example.payment.infrastructure;

/** One {@code payment_operations} row, as read back by {@link PaymentOperationMapper#find}. */
public class PaymentOperationRow {

  private String outcome;
  private String declineCode;
  private String declineReason;

  public String getOutcome() {
    return outcome;
  }

  public void setOutcome(String outcome) {
    this.outcome = outcome;
  }

  public String getDeclineCode() {
    return declineCode;
  }

  public void setDeclineCode(String declineCode) {
    this.declineCode = declineCode;
  }

  public String getDeclineReason() {
    return declineReason;
  }

  public void setDeclineReason(String declineReason) {
    this.declineReason = declineReason;
  }
}
