package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.aipersimmon.ddd.outbox.DeadLetter;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.DeadLetters;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Optional;

/**
 * Reads the {@code aipersimmon_dead_letter} table for an operator, via MyBatis-Plus. Mirrors the
 * dead-letter reading contract: newest failure first, keyed on the table's identity column (see
 * {@code JdbcDeadLetters} for why that column and not {@code failed_at}).
 */
public class MybatisDeadLetters implements DeadLetters {

  private final DeadLetterMapper deadLetterMapper;

  public MybatisDeadLetters(DeadLetterMapper deadLetterMapper) {
    this.deadLetterMapper = deadLetterMapper;
  }

  @Override
  public Slice<DeadLetter> list(Cursor after, int size) {
    if (size < 1) {
      throw new IllegalArgumentException("size must be positive, was " + size);
    }
    // One more than asked for, so a next page is detected without a second count query.
    List<DeadLetterRecord> rows =
        deadLetterMapper.selectList(
            triageColumns()
                .lt(DeadLetterRecord::getId, decode(after))
                .orderByDesc(DeadLetterRecord::getId)
                .last("LIMIT " + (size + 1)));
    List<DeadLetterRecord> page = rows.subList(0, Math.min(rows.size(), size));
    Cursor next = rows.size() > size ? Cursor.of(Long.toString(page.getLast().getId())) : null;
    return new Slice<>(page.stream().map(MybatisDeadLetters::toDeadLetter).toList(), next);
  }

  @Override
  public Optional<DeadLetter> find(String eventId) {
    return Optional.ofNullable(
            deadLetterMapper.selectOne(triageColumns().eq(DeadLetterRecord::getEventId, eventId)))
        .map(MybatisDeadLetters::toDeadLetter);
  }

  /**
   * Everything triage needs and nothing else — in particular not {@code payload}, which answers
   * none of "why did this not go out" and does not belong on an operations screen.
   */
  private static LambdaQueryWrapper<DeadLetterRecord> triageColumns() {
    return new LambdaQueryWrapper<DeadLetterRecord>()
        .select(
            DeadLetterRecord::getId,
            DeadLetterRecord::getEventId,
            DeadLetterRecord::getType,
            DeadLetterRecord::getVersion,
            DeadLetterRecord::getSubject,
            DeadLetterRecord::getTenantId,
            DeadLetterRecord::getOccurredAt,
            DeadLetterRecord::getAttempts,
            DeadLetterRecord::getReason,
            DeadLetterRecord::getLastError,
            DeadLetterRecord::getFailedAt);
  }

  private static long decode(Cursor after) {
    if (after == null) {
      return Long.MAX_VALUE;
    }
    try {
      return Long.parseLong(after.value());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not a cursor issued by this port: " + after.value(), e);
    }
  }

  private static DeadLetter toDeadLetter(DeadLetterRecord record) {
    return new DeadLetter(
        record.getEventId(),
        record.getType(),
        record.getVersion(),
        record.getSubject(),
        record.getTenantId(),
        record.getOccurredAt(),
        record.getAttempts(),
        DeadLetterStore.Reason.valueOf(record.getReason()),
        record.getLastError(),
        record.getFailedAt());
  }
}
