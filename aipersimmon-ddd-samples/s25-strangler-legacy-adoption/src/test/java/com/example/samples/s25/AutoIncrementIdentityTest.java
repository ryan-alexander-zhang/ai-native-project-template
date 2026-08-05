package com.example.samples.s25;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.DuplicateEntityException;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.samples.s25.refunds.application.RefundQuery;
import com.example.samples.s25.refunds.application.RefundView;
import com.example.samples.s25.refunds.domain.Refund;
import com.example.samples.s25.refunds.domain.RefundId;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Auto-increment identity versus the library's write path, and the UUID that goes outward.
 *
 * <p>Two separate questions live in the catalogue's phrase "自增主键与 UUIDv7 并存期怎么处理", and conflating them is what
 * makes it feel hard:
 *
 * <ol>
 *   <li><strong>What is the aggregate's identity?</strong> The legacy {@code bigint}, for the duration. The foreign key,
 *       the legacy code, and a decade of reports all reference it, and changing identity while extracting an aggregate is
 *       two migrations wearing one hat;
 *   <li><strong>What identity goes into a contract?</strong> Never the {@code bigint}. A number meaning "insertion order
 *       in one database" leaks volume, is guessable, and cannot survive two deployments being merged — so {@code public_id}
 *       exists from V2, before any consumer does.
 * </ol>
 *
 * <p>And one mechanical friction that has to be worked around rather than decided: the library will not insert a row whose
 * identity the database assigns.
 */
@Import(AutoIncrementIdentityTest.LetTheDatabaseAssignIt.class)
class AutoIncrementIdentityTest extends StranglerTestBase {

  @Autowired private LetTheDatabaseAssignIt.AssigningRefunds assigning;
  @Autowired private PlatformTransactionManager transactions;

  /**
   * <strong>The friction, and it is not where I expected it.</strong> The insert succeeds; the <em>second</em> write is
   * where an auto-increment table fails.
   *
   * <p>The prediction going in was that {@code saveAggregate} would refuse the insert, because it guards against a row
   * with no primary key. It does guard — in the <em>update</em> path, where a missing key would make the statement match
   * every row of the table. The insert path has no such check, so MyBatis-Plus happily lets the database assign the id.
   *
   * <p>Which is worse than a refusal, and the measurement says why: the row now exists with an id <strong>the application
   * never learned</strong>. The in-memory aggregate still holds whatever identity it was constructed with, the events have
   * been published under that identity, and the version has been advanced. Nothing has failed yet. The failure arrives on
   * the next write to the same aggregate, by which time the misattribution is already in the database and in whatever
   * consumed the events.
   *
   * <p>So the rule for a legacy {@code BIGSERIAL} table is not "the library will stop you" — it is "reserve the id
   * yourself", and the reason is that nothing will stop you. See {@code RefundIds}, and {@code docs/issue/issue-00171}.
   */
  @Test
  void anautoIncrementInsertSucceedsAndTheRowGetsAnIdTheApplicationNeverLearns() {
    long orderId = placeLegacyOrder(10_000);
    inATransaction(() -> assigning.insertWithoutAnId(orderId));

    Long assignedId =
        jdbc.queryForObject("SELECT id FROM legacy_refunds WHERE order_id = ?", Long.class, orderId);
    assertThat(assignedId).as("the insert went through and the database assigned an id").isNotNull();
    assertThat(assignedId)
        .as("and it is not the identity the aggregate was constructed with, which was 1")
        .isNotEqualTo(1L);
  }

  /** And the guard does exist — one write later, where a missing key would have matched every row. */
  @Test
  void thesecondWriteIsWhereTheMissingKeyIsCaught() {
    long orderId = placeLegacyOrder(10_000);
    inATransaction(() -> assigning.insertWithoutAnId(orderId));

    assertThatThrownBy(() -> inATransaction(() -> assigning.updateWithoutAnId(orderId)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("came back from toRow with no primary key value");
  }

  /**
   * <strong>The other schema detail that bites, and it bites on the first write to any pre-migration row.</strong>
   *
   * <p>The library reads {@code version == 0} as "never persisted" and inserts. A legacy table whose version column was
   * added with {@code DEFAULT 0} gives every existing row exactly that value — so the first write to a row that predates
   * the migration is an insert of a row that already exists.
   *
   * <p>The exception is {@code DuplicateEntityException} and its message names two plausible causes, both of which are
   * wrong here: no two creates raced, and no factory forgot {@code restoreVersion}. The column default handed it a zero.
   * Which is why V2 of this sample's migration uses {@code DEFAULT 1}, and why that one character is worth a paragraph.
   */
  @Test
  void aversionOfZeroMakesAPreExistingRowLookLikeANewAggregate() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = legacy.raiseRefund(orderId, 2_500, "raised before the migration");
    assertThat(refundVersion(refundId)).as("V2 uses DEFAULT 1, so this row is saveable").isEqualTo(1);

    // What the same row would look like had the migration said DEFAULT 0.
    jdbc.update("UPDATE legacy_refunds SET version = 0 WHERE id = ?", refundId);
    Refund asIfDefaultZero = refunds.find(new RefundId(refundId)).orElseThrow();
    asIfDefaultZero.approve("ops-anna");

    assertThatThrownBy(() -> inATransaction(() -> refunds.save(asIfDefaultZero)))
        .isInstanceOf(DuplicateEntityException.class)
        .hasMessageContaining("already exists");
  }

  /** The workaround, and it is two lines: take the id from the table's own sequence first. */
  @Test
  void reservingTheIdFromTheTablesOwnSequenceWorks() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    assertThat(refundId).isPositive();
    assertThat(refundRow(refundId)).containsEntry("amount_cents", 2_500L);
  }

  /**
   * And the sequence stays single-sourced, so the two paths cannot collide.
   *
   * <p>This is the reason to use {@code nextval} on the table's own sequence rather than any other allocator: the legacy
   * {@code INSERT} takes its value from the same place. A separate high-water table, or an application-side counter, would
   * be a second source of identity for one column — and the collision would appear the first time the old path inserted.
   */
  @Test
  void bothPathsDrawFromTheSameSequenceSoTheirIdsCannotCollide() {
    long orderId = placeLegacyOrder(10_000);

    long viaNew = entryPoint.raiseRefund(orderId, 100, "new path");
    jdbc.update("UPDATE legacy_refunds SET state = 'REJECTED' WHERE id = ?", viaNew);
    long viaLegacy = legacy.raiseRefund(orderId, 200, "old path");
    long viaNewAgain;
    jdbc.update("UPDATE legacy_refunds SET state = 'REJECTED' WHERE id = ?", viaLegacy);
    viaNewAgain = entryPoint.raiseRefund(orderId, 300, "new path again");

    assertThat(viaLegacy).isGreaterThan(viaNew);
    assertThat(viaNewAgain).isGreaterThan(viaLegacy);
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM legacy_refunds", Long.class))
        .isEqualTo(3);
  }

  /** Every row has a {@code public_id}, whichever path made it — which is what makes the contract safe to publish now. */
  @Test
  void everyRefundHasAPublicIdWhicheverPathCreatedIt() {
    long orderId = placeLegacyOrder(10_000);
    long viaNew = entryPoint.raiseRefund(orderId, 100, "new path");
    jdbc.update("UPDATE legacy_refunds SET state = 'REJECTED' WHERE id = ?", viaNew);
    long viaLegacy = legacy.raiseRefund(orderId, 200, "old path");

    assertThat(refundRow(viaNew).get("public_id")).as("minted by the aggregate").isNotNull();
    assertThat(refundRow(viaLegacy).get("public_id"))
        .as("minted by the column default, because the old path knows nothing about it")
        .isNotNull();
    assertThat(jdbc.queryForObject(
            "SELECT COUNT(DISTINCT public_id) FROM legacy_refunds", Long.class))
        .isEqualTo(2);
  }

  /** And what goes out is the UUID, not the number. */
  @Test
  void thepublishedEventCarriesTheUuidAndNotTheCounter() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    String subject =
        jdbc.queryForObject("SELECT subject FROM aipersimmon_outbox LIMIT 1", String.class);
    RefundView view = queryBus.ask(new RefundQuery(refundId));
    assertThat(subject).isEqualTo(view.publicId());
    assertThat(subject).isNotEqualTo(Long.toString(refundId));
    assertThat(UUID.fromString(subject)).isNotNull();
  }

  /** The identity type refuses a value that could only mean "never inserted". */
  @Test
  void anidOfZeroIsNotAnIdentity() {
    assertThatThrownBy(() -> new RefundId(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("never inserted");
  }

  private void inATransaction(Runnable work) {
    new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
  }

  /**
   * A repository that does the obvious thing on a {@code BIGSERIAL} table: leaves the id to the database.
   *
   * <p><strong>Test scope only.</strong> It is what anybody writes first when the column is auto-increment, and the point
   * is to measure what the library does about it rather than to describe it. A second entity class over the same table is
   * legal because MyBatis-Plus keys its metadata by class.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class LetTheDatabaseAssignIt {

    @Bean
    AssigningRefunds assigningRefunds(AssigningMapper mapper, DomainEvents domainEvents) {
      return new AssigningRefunds(mapper, domainEvents);
    }

    /** {@code IdType.AUTO}: the database supplies it, so {@code toRow} has nothing to put there. */
    @TableName(value = "legacy_refunds", autoResultMap = true)
    public static class AssigningRow implements VersionedRow {

      @TableId(type = IdType.AUTO)
      private Long id;

      private Long orderId;
      private Long amountCents;
      private String reason;
      @com.baomidou.mybatisplus.annotation.TableField(
          typeHandler = com.example.samples.s25.refunds.infrastructure.UuidTypeHandler.class)
      private UUID publicId;
      private String state;
      private String approvedBy;

      @Version private Long version;

      public void setOrderId(Long orderId) {
        this.orderId = orderId;
      }

      public void setAmountCents(Long amountCents) {
        this.amountCents = amountCents;
      }

      public void setReason(String reason) {
        this.reason = reason;
      }

      public void setPublicId(UUID publicId) {
        this.publicId = publicId;
      }

      public void setState(String state) {
        this.state = state;
      }

      public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
      }

      @Override
      public Long getVersion() {
        return version;
      }

      @Override
      public void setVersion(Long version) {
        this.version = version;
      }
    }

    @Mapper
    public interface AssigningMapper extends BaseMapper<AssigningRow> {}

    /** Not an aggregate repository of anything in particular; it exists to reach {@code saveAggregate}. */
    public static class AssigningRefunds
        extends MybatisPlusAggregateRepository<
            com.example.samples.s25.refunds.domain.Refund, AssigningRow> {

      AssigningRefunds(AssigningMapper mapper, DomainEvents domainEvents) {
        super(mapper, domainEvents);
      }

      /** Build a brand-new refund whose row has no id, and hand it to the library. */
      public void insertWithoutAnId(long orderId) {
        saveAggregate(
            com.example.samples.s25.refunds.domain.Refund.raise(
                new RefundId(1), orderId, 100, "no id in the row", false, 10_000, false));
      }

      /** Save an aggregate that is already persisted, so the update path is the one that runs. */
      public void updateWithoutAnId(long orderId) {
        saveAggregate(
            com.example.samples.s25.refunds.domain.Refund.reconstitute(
                new RefundId(1),
                orderId,
                100,
                "no id in the row",
                UUID.randomUUID(),
                "OPEN",
                null,
                1));
      }

      @Override
      protected AssigningRow toRow(com.example.samples.s25.refunds.domain.Refund refund) {
        AssigningRow row = new AssigningRow();
        // No setId: the column is BIGSERIAL, so the obvious thing is to let the database do it.
        row.setOrderId(refund.orderId());
        row.setAmountCents(refund.amountCents());
        row.setReason(refund.reason().orElse(null));
        row.setPublicId(refund.publicId());
        row.setState(refund.state().name());
        row.setApprovedBy(null);
        return row;
      }
    }
  }
}
