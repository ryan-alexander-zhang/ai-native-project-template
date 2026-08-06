package com.aipersimmon.ddd.web.store.mybatisplus;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One fixed-window counter in {@code aipersimmon_web_rate_limit}, identified by the composite
 * (tenant_id, bucket_key, window_start). The bucket key is caller-derived, so tenant is part of its
 * identity and quota is never shared across tenants.
 *
 * <p>No {@code @TableId}, for the same reason as {@link IdempotencyRecord}.
 */
@TableName("aipersimmon_web_rate_limit")
public class RateLimitRecord {

  private String tenantId;
  private String bucketKey;
  private Instant windowStart;
  private Long count;

  public RateLimitRecord() {}

  public RateLimitRecord(String tenantId, String bucketKey, Instant windowStart, Long count) {
    this.tenantId = tenantId;
    this.bucketKey = bucketKey;
    this.windowStart = windowStart;
    this.count = count;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getBucketKey() {
    return bucketKey;
  }

  public void setBucketKey(String bucketKey) {
    this.bucketKey = bucketKey;
  }

  public Instant getWindowStart() {
    return windowStart;
  }

  public void setWindowStart(Instant windowStart) {
    this.windowStart = windowStart;
  }

  public Long getCount() {
    return count;
  }

  public void setCount(Long count) {
    this.count = count;
  }
}
