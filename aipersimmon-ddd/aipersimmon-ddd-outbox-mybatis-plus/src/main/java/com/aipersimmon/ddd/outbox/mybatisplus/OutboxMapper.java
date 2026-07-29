package com.aipersimmon.ddd.outbox.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis-Plus mapper for {@link OutboxRecord}: the inherited {@code insert} (the writer records an
 * event), {@code update} (the relay leases a row, marks it sent, or bumps its attempt count),
 * {@code selectList} (reading a claim back), plus {@link #selectClaimable} for the relay's poll.
 * Registered explicitly by this module's auto-configuration (a {@code MapperFactoryBean}), so the
 * consumer does not need to add it to a {@code @MapperScan}.
 */
public interface OutboxMapper extends BaseMapper<OutboxRecord> {

  /**
   * The rows the relay may claim: unsent, not-given-up ({@code attempts < maxAttempts}), due,
   * carrying no live lease, and the head of their aggregate's live queue — oldest first.
   *
   * <p>Admitting only the head is the per-aggregate ordering guarantee: an aggregate has at most
   * one row claimed anywhere, so a later event cannot overtake an earlier one however many
   * instances poll. An earlier row that was dead-lettered has left the table, and one that has
   * exhausted its attempts is not live; neither blocks. A null/blank subject carries no ordering
   * key.
   *
   * <p>Hand-written SQL (with an explicit outer alias) rather than a wrapper, because the
   * correlated NOT EXISTS needs an unambiguous self-join; kept equivalent to the JDBC starter's
   * query so both backends behave the same. Only the ids are selected — the claim then reads back
   * the rows it won by lease token.
   */
  @Select(
      "SELECT o.event_id FROM aipersimmon_outbox o "
          + "WHERE o.sent = FALSE AND o.attempts < #{maxAttempts} "
          + "AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= #{now}) "
          + "AND (o.lease_until IS NULL OR o.lease_until <= #{now}) "
          + "AND (o.subject IS NULL OR o.subject = '' OR NOT EXISTS ("
          + "SELECT 1 FROM aipersimmon_outbox older WHERE older.subject = o.subject "
          + "AND older.sent = FALSE AND older.attempts < #{maxAttempts} "
          + "AND (older.created_at < o.created_at "
          + "OR (older.created_at = o.created_at AND older.id < o.id)))) "
          + "ORDER BY o.created_at ASC, o.id ASC LIMIT #{batchSize}")
  List<String> selectClaimable(
      @Param("maxAttempts") int maxAttempts,
      @Param("now") Instant now,
      @Param("batchSize") int batchSize);
}
