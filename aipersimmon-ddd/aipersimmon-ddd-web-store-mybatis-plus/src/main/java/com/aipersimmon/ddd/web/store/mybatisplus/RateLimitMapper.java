package com.aipersimmon.ddd.web.store.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis-Plus mapper for {@link RateLimitRecord}: the inherited {@code insert} (opening a window),
 * {@code delete} (sweeping dead windows), {@code selectList} (reading the counter back), plus
 * {@link #increment}. Registered explicitly by this module's auto-configuration (a {@code
 * MapperFactoryBean}), so the consumer does not need to add it to a {@code @MapperScan}.
 */
public interface RateLimitMapper extends BaseMapper<RateLimitRecord> {

  /**
   * Adds one to an existing window's counter, in the database rather than in the caller — read,
   * add-one-in-Java, write-back would lose increments whenever two requests on one bucket overlap,
   * which is the normal case for a rate limiter.
   *
   * <p>Hand-written because the new value is expressed in terms of the old one; a {@code
   * LambdaUpdateWrapper} can only carry it as a raw SQL fragment, which is the same statement with
   * the column name spelled twice.
   *
   * @return 1 when the window's row existed, 0 when the caller must open it
   */
  @Update(
      "UPDATE aipersimmon_web_rate_limit SET count = count + 1"
          + " WHERE tenant_id = #{tenant} AND bucket_key = #{bucket}"
          + " AND window_start = #{windowStart}")
  int increment(
      @Param("tenant") String tenant,
      @Param("bucket") String bucket,
      @Param("windowStart") Instant windowStart);
}
