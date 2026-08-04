package com.example.samples.s10.banking.application;

/**
 * The AT participant, as a port.
 *
 * <p>One method, because AT needs one: the remote service writes inside this thread's global transaction
 * and the coordinator undoes it if the transaction fails. The port hides the transport but deliberately
 * does <em>not</em> hide the protocol — the implementation must propagate the XID, and the participant
 * refuses to write without it.
 */
public interface PointsParticipant {

  /**
   * Award the points, inside the caller's global transaction.
   *
   * @return false when the participant refused for a business reason — which must fail the global
   *     transaction, not be logged and forgotten.
   */
  boolean award(String reference, String accountId, int points);
}
