package com.example.samples.s28.reconciliation.application;

import java.util.List;
import java.util.function.Consumer;

/**
 * The source rows, read in a way that does not depend on how many there are.
 *
 * <p>Both methods exist because {@link ExportSettings.ReadMode} has two answers, and neither is a fallback for
 * the other.
 *
 * <p>What is <em>not</em> here is a {@code List<ExportRowView> all(String period)}. That signature is the bug:
 * it compiles, it passes every test written against a period with fifty rows, and it holds a million objects in
 * heap on the first real month. The type is the design decision, which is why the counterexample lives in test
 * scope and has to be written by hand to exist at all.
 */
public interface ExportSource {

  /**
   * Hand every row of the period to {@code consumer}, one at a time, without materialising the set.
   *
   * <p>Must be called inside a transaction. That is not a style preference: PostgreSQL only opens a
   * server-side cursor when the connection is not in autocommit <em>and</em> a fetch size is set, and when
   * either is missing the driver silently reads the whole result set into memory first. Silently — the query
   * still returns every row, so nothing fails and nothing warns. {@code StreamingExportTest} measures it.
   */
  void streamPeriod(String period, Consumer<ExportRowView> consumer);

  /**
   * One keyset page: the rows of {@code period} with {@code id} greater than {@code afterId}, ascending.
   *
   * <p>Keyset and not {@code OFFSET}: an offset makes the database walk and discard everything before the page,
   * so page 2,000 of a million-row export costs two thousand times page one — and the total cost of the export
   * becomes quadratic in the number of pages. The read-side contract this follows is S20's.
   */
  List<ExportRowView> pageAfter(String period, long afterId, int limit);

  /** How many rows the period has, for a progress denominator. One index scan; not free, but bounded. */
  long countPeriod(String period);
}
