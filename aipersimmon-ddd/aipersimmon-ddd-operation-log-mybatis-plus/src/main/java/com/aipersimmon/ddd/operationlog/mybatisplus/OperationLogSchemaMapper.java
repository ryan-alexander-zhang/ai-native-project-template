package com.aipersimmon.ddd.operationlog.mybatisplus;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;

/**
 * Startup schema probe: a zero-row {@code SELECT} against the audit table, so a missing migration
 * fails fast with a clear message (mirrors {@code JdbcOperationLogSchemaValidator}). The probe
 * names columns rather than a literal, because "table exists" is not "schema is current". Never
 * creates tables.
 */
public interface OperationLogSchemaMapper {

  @Select("SELECT record_id, tenant_id, schema_version FROM aipersimmon_operation_log WHERE 1 = 0")
  List<Map<String, Object>> probe();
}
