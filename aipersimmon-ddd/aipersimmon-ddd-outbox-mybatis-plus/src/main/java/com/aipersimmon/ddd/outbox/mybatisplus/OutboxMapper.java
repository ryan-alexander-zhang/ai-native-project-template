package com.aipersimmon.ddd.outbox.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis-Plus mapper for {@link OutboxRecord}: the inherited {@code insert} (the writer records an
 * event), {@code update} (the relay marks a row sent or bumps its attempt count), plus {@link
 * #selectDue} for the relay's poll. Registered explicitly by this module's auto-configuration (a
 * {@code MapperFactoryBean}), so the consumer does not need to add it to a {@code @MapperScan}.
 */
public interface OutboxMapper extends BaseMapper<OutboxRecord> {

  /**
   * The relay's poll: unsent, not-given-up ({@code attempts < maxAttempts}), due rows, oldest first
   * — but a row is held back while an <em>earlier</em> event of the same subject is still live yet
   * not due (backing off), so a later event never overtakes it across polls. An earlier event that
   * is due is not a blocker (both ride this batch, ordered); nor is a dead-lettered or
   * legacy-abandoned ({@code attempts >= max}) one. A null/blank subject carries no ordering key.
   * Hand-written SQL (with an explicit outer alias) rather than a wrapper, because the correlated
   * NOT EXISTS needs an unambiguous self-join; kept identical to the JDBC starter's query so both
   * backends behave the same.
   */
  @Select(
      "SELECT o.* FROM aipersimmon_outbox o "
          + "WHERE o.sent = FALSE AND o.attempts < #{maxAttempts} "
          + "AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= #{now}) "
          + "AND (o.subject IS NULL OR o.subject = '' OR NOT EXISTS ("
          + "SELECT 1 FROM aipersimmon_outbox older WHERE older.subject = o.subject "
          + "AND older.sent = FALSE AND older.attempts < #{maxAttempts} "
          + "AND older.next_attempt_at IS NOT NULL AND older.next_attempt_at > #{now} "
          + "AND (older.created_at < o.created_at "
          + "OR (older.created_at = o.created_at AND older.id < o.id)))) "
          + "ORDER BY o.created_at ASC, o.id ASC LIMIT #{batchSize}")
  List<OutboxRecord> selectDue(
      @Param("maxAttempts") int maxAttempts,
      @Param("now") Instant now,
      @Param("batchSize") int batchSize);
}
