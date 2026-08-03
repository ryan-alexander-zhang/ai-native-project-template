package com.aipersimmon.ddd.operationlog.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes audit records older than the retention window. Opt-in and deliberately so twice over:
 * deleting data and the right retention are deployment decisions everywhere, and this table is an
 * <em>audit</em> log — removing its rows should be a statement someone can be asked about, never a
 * default. Retention obligations for audit data are often regulatory; the default window is a year,
 * and enabling this asserts that window is long enough.
 *
 * <p>Deletes in id pages ({@code record_id} is the single-column primary key), so the first purge
 * of a long-lived table is many small transactions rather than one giant DELETE. Not lock-guarded:
 * pages are cutoff-bounded and idempotent, so overlapping runs delete disjoint work. The JDBC
 * sibling pages the same way.
 */
public class MybatisPlusOperationLogCleanup {

  private static final Logger log = LoggerFactory.getLogger(MybatisPlusOperationLogCleanup.class);

  private final OperationLogMapper mapper;
  private final Clock clock;
  private final long retentionSeconds;
  private final int batchSize;

  public MybatisPlusOperationLogCleanup(
      OperationLogMapper mapper, Clock clock, long retentionSeconds, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1, was " + batchSize);
    }
    this.mapper = mapper;
    this.clock = clock;
    this.retentionSeconds = retentionSeconds;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${aipersimmon.ddd.operation-log.cleanup.poll-delay-ms:3600000}")
  public void purge() {
    // The record maps recorded_at as OffsetDateTime; the comparison value must match its type.
    OffsetDateTime cutoff = clock.instant().minusSeconds(retentionSeconds).atOffset(ZoneOffset.UTC);
    int total = 0;
    while (true) {
      List<OperationLogRecord> page =
          mapper.selectList(
              new LambdaQueryWrapper<OperationLogRecord>()
                  .select(OperationLogRecord::getRecordId)
                  .lt(OperationLogRecord::getRecordedAt, cutoff)
                  .last("LIMIT " + batchSize));
      if (page.isEmpty()) {
        break;
      }
      total +=
          mapper.delete(
              new LambdaQueryWrapper<OperationLogRecord>()
                  .in(
                      OperationLogRecord::getRecordId,
                      page.stream().map(OperationLogRecord::getRecordId).toList()));
    }
    if (total > 0) {
      log.info(
          "operation-log cleanup removed {} audit records older than {}s", total, retentionSeconds);
    }
  }
}
