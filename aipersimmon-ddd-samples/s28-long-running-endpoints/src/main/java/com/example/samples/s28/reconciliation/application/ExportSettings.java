package com.example.samples.s28.reconciliation.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The knobs, and every one of them is a number somebody has to choose on purpose.
 *
 * <p>They are together in one place because they interact. A fetch size of 500 and a progress interval of 1
 * would publish progress more often than rows arrive; a lease of 30 seconds with a heartbeat of 60 would have
 * every worker lose its own job halfway through.
 */
@ConfigurationProperties(prefix = "s28.export")
public class ExportSettings {

  /** How the source table is read. See {@link ReadMode}. */
  private ReadMode readMode = ReadMode.SNAPSHOT;

  /**
   * Rows per keyset page, in {@link ReadMode#CHUNKED}.
   *
   * <p>It does not govern the cursor's fetch size in {@link ReadMode#SNAPSHOT}, and that is a MyBatis
   * constraint rather than a choice: {@code @Options(fetchSize = ...)} is an annotation value, so it has to be a
   * compile-time constant. The cursor's batch size is therefore fixed in {@code ExportSourceMapper} and this
   * number is only the page size — worth knowing before somebody turns it up expecting the streaming read to
   * change behaviour.
   */
  private int pageSize = 500;

  /** How many rows between progress publications and cancellation checks. */
  private int progressInterval = 1000;

  /** Where progress is written from. See {@link ProgressTransaction}. */
  private ProgressTransaction progressTransaction = ProgressTransaction.OWN_TRANSACTION;

  /** How long a claim is good for without a heartbeat. */
  private Duration lease = Duration.ofSeconds(30);

  /** How often a running worker pushes its lease forward. */
  private Duration heartbeat = Duration.ofSeconds(5);

  /** Where artifacts are written. */
  private String artifactDir = System.getProperty("java.io.tmpdir") + "/s28-exports";

  /**
   * Two honest ways to read a million rows, and they are not equivalent.
   *
   * <p>Choosing between them is the part that cannot be delegated to a framework, so the sample ships both
   * and measures the difference rather than picking one and calling it best practice.
   */
  public enum ReadMode {
    /**
     * One read-only transaction, one server-side cursor. The export sees the period exactly as it was when
     * the query began, which is what "the June file" usually has to mean.
     *
     * <p>The cost is a transaction open for the whole export. On PostgreSQL that holds back the xmin horizon,
     * so autovacuum cannot reclaim anything newer for as long as it runs — a four-hour export is a four-hour
     * hole in the vacuum schedule, and it is the reason a DBA will ask about this endpoint.
     */
    SNAPSHOT,

    /**
     * Keyset pages, each in its own short transaction: {@code WHERE period = ? AND id > ? ORDER BY id LIMIT n}.
     * No long transaction, no vacuum pressure, and a worker that dies loses one page rather than an hour.
     *
     * <p>The cost is that there is no single picture. Rows inserted between pages appear if they sort after
     * the cursor and do not if they sort before it, so the file is a union of moments rather than a snapshot.
     * {@code StreamingExportTest} inserts a row mid-export and measures exactly that difference.
     */
    CHUNKED
  }

  /** Where a progress tick's transaction comes from. */
  public enum ProgressTransaction {
    /**
     * Each tick on its own connection, committing immediately. The only mode in which a progress query can
     * answer anything while the export is running.
     */
    OWN_TRANSACTION,

    /**
     * Joins whatever transaction the export is already in. The shape that looks right, costs no extra
     * connection, and publishes nothing at all until the export ends — at which point progress is redundant.
     * Reachable only by setting the property; {@code ProgressIsNotAnInvariantTest} measures what it hides.
     */
    SAME_TRANSACTION
  }

  public ReadMode getReadMode() {
    return readMode;
  }

  public void setReadMode(ReadMode readMode) {
    this.readMode = readMode;
  }

  public int getPageSize() {
    return pageSize;
  }

  public void setPageSize(int pageSize) {
    this.pageSize = pageSize;
  }

  public int getProgressInterval() {
    return progressInterval;
  }

  public void setProgressInterval(int progressInterval) {
    this.progressInterval = progressInterval;
  }

  public ProgressTransaction getProgressTransaction() {
    return progressTransaction;
  }

  public void setProgressTransaction(ProgressTransaction progressTransaction) {
    this.progressTransaction = progressTransaction;
  }

  public Duration getLease() {
    return lease;
  }

  public void setLease(Duration lease) {
    this.lease = lease;
  }

  public Duration getHeartbeat() {
    return heartbeat;
  }

  public void setHeartbeat(Duration heartbeat) {
    this.heartbeat = heartbeat;
  }

  public String getArtifactDir() {
    return artifactDir;
  }

  public void setArtifactDir(String artifactDir) {
    this.artifactDir = artifactDir;
  }
}
