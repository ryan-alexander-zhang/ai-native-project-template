package com.example.samples.s10.banking.application;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.LocalTCC;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;

/**
 * The TCC participant, as a port — and the three-phase contract is the port, which is the point.
 *
 * <p><strong>Why this interface is in the application layer and not in infrastructure.</strong> Its
 * annotations belong to Seata, so the instinct is to hide it away. But Try/Confirm/Cancel is not a
 * transport detail: it is a use case decomposed into a promise and a settlement, and the orchestrator has
 * to call all three phases' worth of meaning even though it only calls one of them. Putting it in
 * infrastructure would mean the application layer either imports infrastructure (which the architecture
 * rules forbid, correctly) or wraps a three-phase contract in a one-method port and loses the contract.
 * The implementation — HTTP calls to the other service — is infrastructure and lives there.
 *
 * <p><strong>What Seata guarantees about the other two methods, and what it does not.</strong> It will call
 * {@code confirm} or {@code cancel} exactly one of, eventually, retrying until one of them succeeds. It
 * does not promise <em>when</em>, does not promise only once, and does not promise the same thread — the
 * calls arrive on Seata's own RM threads, so nothing bound to the request thread is there any more. That
 * last one is the trap: the tenant is a thread-local, so the implementation has to carry it through
 * {@link BusinessActionContext} and rebind it. Under AT the same problem does not exist, because AT's
 * rollback is executed by Seata against the plain JDBC connection and never re-enters application code.
 */
@LocalTCC
public interface PointsAwardAction {

  /**
   * Phase one: ask the other service to promise the points.
   *
   * <p>Every parameter that Confirm or Cancel will need has to be marked with {@code
   * @BusinessActionContextParameter}, because the marked values are the only thing Seata persists for the
   * later phases. An unmarked parameter is available now and gone by the time Cancel runs.
   *
   * <p><strong>And the markers are on the implementation, not here — deliberately, because here they do
   * nothing.</strong> Seata reads the parameter annotations from the method it actually invoked, which is
   * {@code HttpPointsAwardAction.tryAward}, and Java does not inherit parameter annotations from an
   * interface. Annotating only this interface produces a TCC branch whose action context holds Seata's own
   * bookkeeping and none of the business values — measured: {@code actionContext} came back with just
   * {@code sys::commit}, {@code sys::rollback} and friends, Confirm then failed on the missing value, and
   * the coordinator retried it every second forever while the account row stayed locked. The failure is a
   * retry storm rather than an error, so nothing points at the cause.
   *
   * <p>Which makes this the one place in the sample where the annotation and the contract are deliberately
   * kept apart: the contract is documented here, the annotations live where they are read.
   */
  @TwoPhaseBusinessAction(
      name = "s10PointsAward",
      commitMethod = "confirmAward",
      rollbackMethod = "cancelAward")
  boolean tryAward(
      BusinessActionContext context,
      String reference,
      String accountId,
      int points,
      String tenant);

  /** Phase two, happy path. Must be idempotent: Seata retries until it is told yes. */
  boolean confirmAward(BusinessActionContext context);

  /**
   * Phase two, unhappy path. Must be idempotent, must tolerate a Try that never ran, and must leave a mark
   * so a Try arriving afterwards can be refused.
   */
  boolean cancelAward(BusinessActionContext context);
}
