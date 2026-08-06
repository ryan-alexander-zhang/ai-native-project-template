package com.aipersimmon.ddd.inbox.mybatisplus;

import java.util.List;
import org.apache.ibatis.annotations.Select;

/**
 * Startup schema probe: a zero-row {@code SELECT} against the inbox table, so a missing migration
 * fails fast with a clear message. The probe names a column a later migration added rather than a
 * literal, because "table exists" is not "schema is current". Never creates tables.
 */
public interface InboxSchemaMapper {

  @Select("SELECT tenant_id FROM aipersimmon_inbox WHERE 1 = 0")
  List<String> probe();
}
