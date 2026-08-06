package com.aipersimmon.ddd.web.store.mybatisplus;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;

/**
 * Startup schema probe: a zero-row {@code SELECT} per web-store table, so a missing or stale
 * migration fails fast with a clear message. Each probe names columns the later migrations added
 * rather than a literal, because "table exists" is not "schema is current" once a component has
 * more than one migration. Never creates tables.
 */
public interface WebStoreSchemaMapper {

  @Select(
      "SELECT tenant_id, principal, fingerprint, state FROM aipersimmon_web_idempotency"
          + " WHERE 1 = 0")
  List<Map<String, Object>> probeIdempotency();

  @Select("SELECT tenant_id FROM aipersimmon_web_nonce WHERE 1 = 0")
  List<Map<String, Object>> probeNonce();

  @Select("SELECT tenant_id FROM aipersimmon_web_rate_limit WHERE 1 = 0")
  List<Map<String, Object>> probeRateLimit();
}
