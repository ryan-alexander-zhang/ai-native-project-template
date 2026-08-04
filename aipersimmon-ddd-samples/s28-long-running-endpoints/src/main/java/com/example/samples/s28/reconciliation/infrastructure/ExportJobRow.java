package com.example.samples.s28.reconciliation.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * The job row.
 *
 * <p>Two columns here are written by hand-written SQL as well as by the aggregate: {@code lease_owner} and
 * {@code lease_until}, plus {@code status}, {@code attempt} and {@code started_at}, which the claim statement
 * sets. That overlap is the thing to be careful about, and it is safe for one reason — the claim advances
 * {@code version}, so any aggregate snapshot loaded before it can no longer be written. Take that away and the
 * two writers are simply racing.
 */
@TableName("s28_export_job")
class ExportJobRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String period;
  private String format;
  private String status;
  private Integer attempt;
  private String leaseOwner;
  private Instant leaseUntil;
  private Boolean cancelRequested;
  private String artifactPath;
  private Long artifactBytes;
  private Long artifactRows;
  private String failure;
  private Instant submittedAt;
  private Instant startedAt;
  private Instant finishedAt;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  String getPeriod() {
    return period;
  }

  void setPeriod(String period) {
    this.period = period;
  }

  String getFormat() {
    return format;
  }

  void setFormat(String format) {
    this.format = format;
  }

  String getStatus() {
    return status;
  }

  void setStatus(String status) {
    this.status = status;
  }

  Integer getAttempt() {
    return attempt;
  }

  void setAttempt(Integer attempt) {
    this.attempt = attempt;
  }

  String getLeaseOwner() {
    return leaseOwner;
  }

  void setLeaseOwner(String leaseOwner) {
    this.leaseOwner = leaseOwner;
  }

  Instant getLeaseUntil() {
    return leaseUntil;
  }

  void setLeaseUntil(Instant leaseUntil) {
    this.leaseUntil = leaseUntil;
  }

  Boolean getCancelRequested() {
    return cancelRequested;
  }

  void setCancelRequested(Boolean cancelRequested) {
    this.cancelRequested = cancelRequested;
  }

  String getArtifactPath() {
    return artifactPath;
  }

  void setArtifactPath(String artifactPath) {
    this.artifactPath = artifactPath;
  }

  Long getArtifactBytes() {
    return artifactBytes;
  }

  void setArtifactBytes(Long artifactBytes) {
    this.artifactBytes = artifactBytes;
  }

  Long getArtifactRows() {
    return artifactRows;
  }

  void setArtifactRows(Long artifactRows) {
    this.artifactRows = artifactRows;
  }

  String getFailure() {
    return failure;
  }

  void setFailure(String failure) {
    this.failure = failure;
  }

  Instant getSubmittedAt() {
    return submittedAt;
  }

  void setSubmittedAt(Instant submittedAt) {
    this.submittedAt = submittedAt;
  }

  Instant getStartedAt() {
    return startedAt;
  }

  void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  Instant getFinishedAt() {
    return finishedAt;
  }

  void setFinishedAt(Instant finishedAt) {
    this.finishedAt = finishedAt;
  }

  @Override
  public Long getVersion() {
    return version;
  }

  @Override
  public void setVersion(Long version) {
    this.version = version;
  }
}
