package com.aipersimmon.ddd.web.store.mybatisplus;

import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatis-Plus-backed {@link ReplayGuard}: a nonce is recorded in {@code aipersimmon_web_nonce} on
 * first sight and reported as seen on reuse. The primary key provides the single-use guarantee
 * across instances; an expired entry for the nonce is purged first so the window can roll over.
 */
public class MybatisPlusReplayGuard implements ReplayGuard {

  private final NonceMapper mapper;
  private final Clock clock;

  public MybatisPlusReplayGuard(NonceMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  @Override
  public boolean seenBefore(String nonce, Duration ttl) {
    Instant now = clock.instant();
    String tenant = tenant();
    mapper.delete(
        new LambdaQueryWrapper<NonceRecord>()
            .eq(NonceRecord::getTenantId, tenant)
            .eq(NonceRecord::getNonce, nonce)
            .le(NonceRecord::getExpiresAt, now));
    try {
      mapper.insert(new NonceRecord(tenant, nonce, now, now.plus(ttl)));
      return false;
    } catch (DuplicateKeyException e) {
      return true;
    }
  }

  /**
   * The tenant that scopes this nonce, read from the ambient {@link TenantContext} bound on the
   * request edge; the root sentinel when tenancy is off. The nonce is client-supplied, so tenant is
   * part of its identity — see the composite primary key.
   */
  private static String tenant() {
    return TenantContext.effective().value();
  }
}
