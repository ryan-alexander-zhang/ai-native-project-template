package com.aipersimmon.ddd.processmanager.mybatisplus.autoconfigure;

import java.util.List;
import org.apache.ibatis.annotations.Select;

/**
 * Startup schema probe: a zero-row {@code SELECT} per process table, so a missing migration fails
 * fast with a clear message (mirrors {@code JdbcProcessSchemaValidator}). Never creates tables.
 */
public interface ProcessSchemaMapper {

  @Select("SELECT 1 FROM aipersimmon_process_instance WHERE 1 = 0")
  List<Integer> probeInstance();

  @Select("SELECT 1 FROM aipersimmon_process_transition WHERE 1 = 0")
  List<Integer> probeTransition();

  @Select("SELECT 1 FROM aipersimmon_process_effect WHERE 1 = 0")
  List<Integer> probeEffect();

  @Select("SELECT 1 FROM aipersimmon_process_deadline WHERE 1 = 0")
  List<Integer> probeDeadline();
}
