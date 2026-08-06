package com.aipersimmon.ddd.outbox.mybatisplus;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;

/**
 * Startup schema probe: a zero-row {@code SELECT} per outbox table, so a missing or stale migration
 * fails fast with a clear message. Each probe names columns the later migrations added rather than
 * a literal, because "table exists" is not "schema is current". Never creates tables.
 */
public interface OutboxSchemaMapper {

  @Select("SELECT tenant_id, lease_token, destination FROM aipersimmon_outbox WHERE 1 = 0")
  List<Map<String, Object>> probeOutbox();

  @Select("SELECT tenant_id, destination FROM aipersimmon_dead_letter WHERE 1 = 0")
  List<Map<String, Object>> probeDeadLetter();

  @Select("SELECT name, lock_until FROM shedlock WHERE 1 = 0")
  List<Map<String, Object>> probeShedlock();
}
