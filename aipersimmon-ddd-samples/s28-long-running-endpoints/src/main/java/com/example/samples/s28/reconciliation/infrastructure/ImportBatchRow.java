package com.example.samples.s28.reconciliation.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/** The batch row. Small, and it stays small however many chunks arrive — see {@code ChunkTally}. */
@TableName("s28_import_batch")
class ImportBatchRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private Integer declaredChunks;
  private String status;
  private Long acceptedRows;
  private String failure;
  private Instant openedAt;
  private Instant completedAt;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  Integer getDeclaredChunks() {
    return declaredChunks;
  }

  void setDeclaredChunks(Integer declaredChunks) {
    this.declaredChunks = declaredChunks;
  }

  String getStatus() {
    return status;
  }

  void setStatus(String status) {
    this.status = status;
  }

  Long getAcceptedRows() {
    return acceptedRows;
  }

  void setAcceptedRows(Long acceptedRows) {
    this.acceptedRows = acceptedRows;
  }

  String getFailure() {
    return failure;
  }

  void setFailure(String failure) {
    this.failure = failure;
  }

  Instant getOpenedAt() {
    return openedAt;
  }

  void setOpenedAt(Instant openedAt) {
    this.openedAt = openedAt;
  }

  Instant getCompletedAt() {
    return completedAt;
  }

  void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
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
