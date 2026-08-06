package com.aipersimmon.ddd.web.store.mybatisplus;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * One idempotent attempt in {@code aipersimmon_web_idempotency}, identified by the composite
 * (tenant_id, principal, idempotency_key). Uses MyBatis-Plus {@code @TableName}, not a JPA
 * {@code @Entity}, so it never affects a consumer's entity scanning.
 *
 * <p>No field is marked {@code @TableId}. The identity is the triple, which MyBatis-Plus cannot
 * express as a single id, and the client-supplied key alone is unique neither across tenants nor
 * across callers — that is the whole point of the composite key. Every access therefore goes
 * through a fully-qualified {@code LambdaQueryWrapper} (see {@link MybatisPlusIdempotencyStore}),
 * and the id-based {@code BaseMapper} methods are deliberately not generated, so a row cannot be
 * addressed by the key alone and hand one caller another caller's stored response.
 *
 * <p>{@code responseStatus}, {@code responseBody} and {@code responseHeaders} are null while the
 * row is a {@code PENDING} claim: the claim is inserted before the request runs, so there is no
 * outcome yet.
 */
@TableName("aipersimmon_web_idempotency")
public class IdempotencyRecord {

  private String tenantId;
  private String principal;
  private String idempotencyKey;
  private String fingerprint;
  private String state;
  private Integer responseStatus;
  private byte[] responseBody;
  private String responseHeaders;
  private Instant createdAt;
  private Instant expiresAt;

  public IdempotencyRecord() {}

  public IdempotencyRecord(
      String tenantId,
      String principal,
      String idempotencyKey,
      String fingerprint,
      String state,
      Instant createdAt,
      Instant expiresAt) {
    this.tenantId = tenantId;
    this.principal = principal;
    this.idempotencyKey = idempotencyKey;
    this.fingerprint = fingerprint;
    this.state = state;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getPrincipal() {
    return principal;
  }

  public void setPrincipal(String principal) {
    this.principal = principal;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public void setFingerprint(String fingerprint) {
    this.fingerprint = fingerprint;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public Integer getResponseStatus() {
    return responseStatus;
  }

  public void setResponseStatus(Integer responseStatus) {
    this.responseStatus = responseStatus;
  }

  /**
   * Copied in and out, like every other byte[] carrier in the library: the row must not hand out a
   * handle on the response body a caller could edit after the fact.
   */
  public byte[] getResponseBody() {
    return responseBody == null ? null : responseBody.clone();
  }

  public void setResponseBody(byte[] responseBody) {
    this.responseBody = responseBody == null ? null : responseBody.clone();
  }

  public String getResponseHeaders() {
    return responseHeaders;
  }

  public void setResponseHeaders(String responseHeaders) {
    this.responseHeaders = responseHeaders;
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
