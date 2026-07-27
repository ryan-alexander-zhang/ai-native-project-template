package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;
import com.aipersimmon.ddd.outbox.DeadLetter;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.DeadLetters;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads the {@code aipersimmon_dead_letter} table for an operator.
 *
 * <p>Paged by the table's own identity column rather than by {@code failed_at}: the id is assigned
 * when the relay gives up, so descending id is already "newest failure first", it is unique — two
 * rows abandoned in the same millisecond still have a total order — and it is the primary key, so
 * no extra index is needed. The cursor is that id as a string, which callers must treat as opaque;
 * the column it comes from is free to change.
 *
 * <p>The payload column is never selected. Triage does not need it, and a listing that carried
 * every message body would put event contents on an operations screen for no benefit.
 */
public class JdbcDeadLetters implements DeadLetters {

  private static final String COLUMNS =
      "id, event_id, type, version, subject, tenant_id, occurred_at, "
          + "attempts, reason, last_error, failed_at";
  private static final String SELECT_PAGE =
      "SELECT " + COLUMNS + " FROM aipersimmon_dead_letter WHERE id < ? ORDER BY id DESC LIMIT ?";
  private static final String SELECT_ONE =
      "SELECT " + COLUMNS + " FROM aipersimmon_dead_letter WHERE event_id = ?";

  private final JdbcTemplate jdbc;

  public JdbcDeadLetters(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Slice<DeadLetter> list(Cursor after, int size) {
    if (size < 1) {
      throw new IllegalArgumentException("size must be positive, was " + size);
    }
    // One more than asked for: the extra row is how we know a next page exists without counting.
    // An absent cursor starts above every id, so the first page needs no second statement.
    List<Row> rows = jdbc.query(SELECT_PAGE, (rs, index) -> readRow(rs), decode(after), size + 1);
    List<Row> page = rows.subList(0, Math.min(rows.size(), size));
    Cursor next = rows.size() > size ? Cursor.of(Long.toString(page.getLast().id())) : null;
    return new Slice<>(page.stream().map(Row::deadLetter).toList(), next);
  }

  @Override
  public Optional<DeadLetter> find(String eventId) {
    return jdbc.query(SELECT_ONE, (rs, index) -> readRow(rs).deadLetter(), eventId).stream()
        .findFirst();
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

  private static Row readRow(ResultSet rs) throws SQLException {
    return new Row(
        rs.getLong("id"),
        new DeadLetter(
            rs.getString("event_id"),
            rs.getString("type"),
            rs.getInt("version"),
            rs.getString("subject"),
            rs.getString("tenant_id"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getInt("attempts"),
            DeadLetterStore.Reason.valueOf(rs.getString("reason")),
            rs.getString("last_error"),
            rs.getTimestamp("failed_at").toInstant()));
  }

  /** A read row plus the id the cursor is cut from — the id itself stays out of the port's view. */
  private record Row(long id, DeadLetter deadLetter) {}
}
