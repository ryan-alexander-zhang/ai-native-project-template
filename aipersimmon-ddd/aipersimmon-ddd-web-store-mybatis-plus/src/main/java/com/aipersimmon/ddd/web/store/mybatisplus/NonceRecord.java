package com.aipersimmon.ddd.web.store.mybatisplus;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One seen nonce in {@code aipersimmon_web_nonce}, identified by the composite (tenant_id, nonce).
 * The nonce is client-supplied, so tenant is part of its identity rather than a data column — see
 * the migration that added it.
 *
 * <p>No {@code @TableId}, for the same reason as {@link IdempotencyRecord}: the identity is
 * composite, and one tenant must not be able to consume another tenant's nonce.
 */
@TableName("aipersimmon_web_nonce")
public class NonceRecord {

  private String tenantId;
  private String nonce;
  private Instant createdAt;
  private Instant expiresAt;

  public NonceRecord() {}

  public NonceRecord(String tenantId, String nonce, Instant createdAt, Instant expiresAt) {
    this.tenantId = tenantId;
    this.nonce = nonce;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getNonce() {
    return nonce;
  }

  public void setNonce(String nonce) {
    this.nonce = nonce;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }
}
