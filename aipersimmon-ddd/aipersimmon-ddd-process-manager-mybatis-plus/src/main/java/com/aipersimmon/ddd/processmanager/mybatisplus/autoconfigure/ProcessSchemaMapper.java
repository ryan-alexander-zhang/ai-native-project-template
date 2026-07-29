package com.aipersimmon.ddd.processmanager.mybatisplus.autoconfigure;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;

/**
 * Startup schema probe: a zero-row {@code SELECT} per process table, so a missing or stale
 * migration fails fast with a clear message (mirrors {@code JdbcProcessSchemaValidator}). Each
 * probe names the columns the latest migrations added rather than a literal, because "table exists"
 * is not "schema is current". Never creates tables.
 */
public interface ProcessSchemaMapper {

  @Select("SELECT tenant_id FROM aipersimmon_process_instance WHERE 1 = 0")
  List<String> probeInstance();

  @Select("SELECT tenant_id, replayed_at FROM aipersimmon_process_transition WHERE 1 = 0")
  List<Map<String, Object>> probeTransition();

  @Select("SELECT tenant_id FROM aipersimmon_process_effect WHERE 1 = 0")
  List<String> probeEffect();

  @Select("SELECT tenant_id FROM aipersimmon_process_deadline WHERE 1 = 0")
  List<String> probeDeadline();
}
