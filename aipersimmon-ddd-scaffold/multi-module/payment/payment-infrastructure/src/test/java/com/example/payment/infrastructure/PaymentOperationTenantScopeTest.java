package com.example.payment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.tenancy.MissingTenantException;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.TenantEnforcement;
import com.aipersimmon.ddd.tenancy.TenantId;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.payment.domain.PaymentDecision;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Which tenant a payment operation is stamped with, and what happens when there is no answer.
 *
 * <p>{@code payment_operations} is deliberately outside the tenant-line interceptor's allow-list,
 * so this class stamps the column itself — which makes "what if no tenant is bound" a question it
 * has to get right rather than one the framework answers for it.
 *
 * <p>It used to answer {@code current().orElse(Tenants.ROOT)}: on a thread that had lost its
 * binding, the row silently went to the shared bucket. That is invisible until someone reads the
 * table and finds another tenant's payment in it, and the {@code find} above would then also miss
 * the real tenant's row and re-authorize an operation already handled. The decision belongs to
 * {@code TenantContext.effective()}, which makes it once from the deployment's tenancy mode.
 */
class PaymentOperationTenantScopeTest {

  private static final TenantEnforcement ENFORCEMENT = new TenantEnforcement();

  private final RecordingMapper mapper = new RecordingMapper();
  private final MyBatisPaymentOperations operations =
      new MyBatisPaymentOperations(
          mapper, java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));

  @AfterEach
  void resetTenancyMode() {
    ENFORCEMENT.disable();
    TenantContext.clear();
  }

  @Test
  void theBoundTenantIsStampedOnTheRow() {
    ENFORCEMENT.enable();
    TenantContext.set(new TenantId("acme"));

    operations.record("op-1", new PaymentDecision.Authorized());

    assertEquals(List.of("acme/op-1"), mapper.recorded);
  }

  /**
   * The regression. With multi-tenancy on and nothing bound, the write must refuse — not quietly
   * land in {@code __root__} alongside every other tenant that ever lost its binding.
   */
  @Test
  void anUnboundThreadIsRefusedRatherThanWritingToTheSharedBucket() {
    ENFORCEMENT.enable();

    assertThrows(
        MissingTenantException.class,
        () -> operations.record("op-2", new PaymentDecision.Authorized()));

    assertEquals(List.of(), mapper.recorded, "nothing may reach the table");
  }

  /** Reads are scoped the same way, or a lookup answers from the wrong bucket. */
  @Test
  void readsAreRefusedOnAnUnboundThreadToo() {
    ENFORCEMENT.enable();

    assertThrows(MissingTenantException.class, () -> operations.find("op-3"));
  }

  /**
   * Single-tenant is N=1 multi-tenancy: with tenancy off the sentinel is the right answer, not a
   * fallback. Same call, opposite outcome, decided by the deployment rather than by this class.
   */
  @Test
  void withTenancyOffTheSentinelIsStillUsed() {
    operations.record("op-4", new PaymentDecision.Authorized());

    assertEquals(List.of(Tenants.ROOT.value() + "/op-4"), mapper.recorded);
  }

  /** Records what the mapper was asked to write, and nothing else. */
  private static final class RecordingMapper implements PaymentOperationMapper {
    private final List<String> recorded = new ArrayList<>();

    @Override
    public PaymentOperationRow find(String tenantId, String operationId) {
      return null;
    }

    @Override
    public void record(
        String tenantId,
        String operationId,
        String outcome,
        String declineCode,
        String declineReason,
        java.time.Instant recordedAt) {
      recorded.add(tenantId + "/" + operationId);
    }

    @Override
    public int markVoided(String tenantId, String operationId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int purgeRecordedBefore(java.time.Instant cutoff) {
      throw new UnsupportedOperationException();
    }
  }
}
