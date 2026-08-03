package com.example.payment.infrastructure;

import java.time.Instant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * The three statements the payment operation log needs. A plain MyBatis mapper rather than a
 * MyBatis-Plus {@code BaseMapper}: there is no aggregate here, no identity to load and no version
 * to check — just a claim, a lookup, and the expiry of both.
 *
 * <p>Both run on the shared {@code SqlSession}, so they join whatever transaction the command bus
 * opened. That is the entire point of the table (see {@code PaymentOperations}); a log written
 * outside the caller's transaction would leave a claim behind when the publish rolls back.
 */
@Mapper
public interface PaymentOperationMapper {

  /**
   * The recorded outcome, or {@code null}. Returns the row rather than a decision so the mapping
   * back into the sealed {@code PaymentDecision} stays in the adapter, out of the SQL layer.
   */
  @Select(
      """
      SELECT outcome, decline_code, decline_reason
        FROM payment.payment_operations
       WHERE tenant_id = #{tenantId} AND operation_id = #{operationId}
      """)
  PaymentOperationRow find(
      @Param("tenantId") String tenantId, @Param("operationId") String operationId);

  /**
   * Claim the operation. Deliberately no upsert: a duplicate must raise the primary-key violation
   * rather than be absorbed, because that violation is how two concurrent first deliveries are
   * resolved — the loser rolls back and its retry republishes the winner's decision.
   */
  @Insert(
      """
      INSERT INTO payment.payment_operations
             (tenant_id, operation_id, outcome, decline_code, decline_reason, recorded_at)
      VALUES (#{tenantId}, #{operationId}, #{outcome}, #{declineCode}, #{declineReason},
              #{recordedAt})
      """)
  void record(
      @Param("tenantId") String tenantId,
      @Param("operationId") String operationId,
      @Param("outcome") String outcome,
      @Param("declineCode") String declineCode,
      @Param("declineReason") String declineReason,
      @Param("recordedAt") Instant recordedAt);

  /**
   * Turn an AUTHORIZED outcome into VOIDED. The {@code outcome = 'AUTHORIZED'} predicate is the
   * idempotency: a redelivered void, or a void of a declined/already-voided operation, matches zero
   * rows and changes nothing. The one sanctioned UPDATE against this table — releasing a hold
   * undoes the irreversible act's reservation, it does not re-decide it.
   */
  @Update(
      """
      UPDATE payment.payment_operations
         SET outcome = 'VOIDED'
       WHERE tenant_id = #{tenantId} AND operation_id = #{operationId}
         AND outcome = 'AUTHORIZED'
      """)
  int markVoided(@Param("tenantId") String tenantId, @Param("operationId") String operationId);

  /**
   * Drop operations recorded before {@code cutoff}, across all tenants. Tenant-less on purpose,
   * like the framework's own background-polled deletes: expiry is a property of age, and a relay
   * running under no tenant must still be able to clear every tenant's expired rows.
   *
   * <p>Named {@code purge}, matching {@code PaymentOperationCleanup.purge()} — and it has to stay
   * that way. SpotBugs decides whether a type is mutable partly by guessing setters from
   * method-name prefixes, and {@code delete} is one of them. Calling this {@code
   * deleteRecordedBefore} makes this whole interface "mutable" in its eyes, which then raises
   * {@code EI_EXPOSE_REP2} against <em>every class that holds a mapper</em> — including {@code
   * MyBatisPaymentOperations}, which has nothing to do with the change. The report lands on the
   * holder's constructor and the cause is in this method's name, with nothing connecting them.
   *
   * @return how many rows went, so the caller can say so
   */
  @Delete("DELETE FROM payment.payment_operations WHERE recorded_at < #{cutoff}")
  int purgeRecordedBefore(@Param("cutoff") Instant cutoff);
}
