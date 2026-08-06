package com.aipersimmon.ddd.web.store.mybatisplus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis-Plus mapper for {@link IdempotencyRecord}: the inherited {@code insert} (taking a claim),
 * {@code delete} (expiring or abandoning one), {@code selectList} (reading back what won the race),
 * plus {@link #complete} for storing the outcome. Registered explicitly by this module's
 * auto-configuration (a {@code MapperFactoryBean}), so the consumer does not need to add it to a
 * {@code @MapperScan}.
 */
public interface IdempotencyMapper extends BaseMapper<IdempotencyRecord> {

  /**
   * Records the outcome on a claim this caller won, moving the row from {@code PENDING} to {@code
   * COMPLETE} and re-purposing {@code expires_at} from the claim lease to the end of the retry
   * window.
   *
   * <p>Hand-written rather than a {@code LambdaUpdateWrapper}, because {@code responseBody} is the
   * one value here whose type MyBatis must know statically: bound through a wrapper it would arrive
   * as an untyped parameter and be resolved by the runtime class of the value, which is how a
   * {@code byte[]} ends up going to the driver as a serialised object on some dialects. Declaring
   * it in the signature pins the {@code byte[]} type handler.
   */
  @Update(
      "UPDATE aipersimmon_web_idempotency"
          + " SET state = #{state}, response_status = #{status}, response_body = #{body},"
          + " response_headers = #{headers}, expires_at = #{expiresAt}"
          + " WHERE tenant_id = #{tenant} AND principal = #{principal}"
          + " AND idempotency_key = #{key}")
  int complete(
      @Param("tenant") String tenant,
      @Param("principal") String principal,
      @Param("key") String key,
      @Param("state") String state,
      @Param("status") int status,
      @Param("body") byte[] body,
      @Param("headers") String headers,
      @Param("expiresAt") Instant expiresAt);
}
