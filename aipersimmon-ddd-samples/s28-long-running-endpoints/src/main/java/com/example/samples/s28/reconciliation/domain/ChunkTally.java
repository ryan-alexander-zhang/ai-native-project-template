package com.example.samples.s28.reconciliation.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Which chunks have arrived, handed to the aggregate as an argument.
 *
 * <p>This is the shape that keeps the receipts out of the aggregate without giving up the rule. The batch
 * cannot complete until every declared chunk is present — a genuine invariant — but the receipts themselves
 * bear no invariant between them: chunk 7 arriving tells you nothing about chunk 8, and a thousand-chunk
 * upload would give the batch a thousand-element child collection rewritten in full on every save.
 *
 * <p>So the application counts, and passes the count in. The same arrangement S27 used for its erasure gate.
 * What makes it safe is that the decision is still made inside a version-checked write: two concurrent
 * completions both read the same tally, both call {@code complete}, and one of them loses on the version.
 *
 * @param received the chunk numbers on record, ascending
 * @param rows how many rows those chunks carried in total
 */
@ValueObject
public record ChunkTally(SortedSet<Integer> received, long rows) {

  public ChunkTally {
    received = received == null ? new TreeSet<>() : new TreeSet<>(received);
    if (rows < 0) {
      throw new IllegalArgumentException("row count must not be negative");
    }
  }

  public static ChunkTally of(List<Integer> chunkNumbers, long rows) {
    return new ChunkTally(new TreeSet<>(chunkNumbers), rows);
  }

  /** The numbers between 1 and {@code declared} that are not on record, so a client can be told. */
  public List<Integer> missingOf(int declared) {
    return java.util.stream.IntStream.rangeClosed(1, declared)
        .filter(n -> !received.contains(n))
        .boxed()
        .toList();
  }
}
