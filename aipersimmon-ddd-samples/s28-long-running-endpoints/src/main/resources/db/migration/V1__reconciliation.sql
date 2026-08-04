-- The reconciliation context: one table of source rows, one job aggregate per direction, and two tables
-- that deliberately do NOT belong to any aggregate.

-- ---------------------------------------------------------------------------------------------------
-- The data an export reads. Not an aggregate and not modelled as one: these rows arrive from settlement
-- and this context only ever reads them. A million of them is the point of the scenario.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE s28_export_row (
  id           BIGSERIAL    PRIMARY KEY,
  period       VARCHAR(7)   NOT NULL,
  order_ref    VARCHAR(64)  NOT NULL,
  amount_cents BIGINT       NOT NULL,
  note         TEXT         NOT NULL
);

-- (period, id) rather than (period): the keyset-paged read mode asks for "the next page of this period
-- after id X", and a leading index on period alone would make every page a fresh sort.
CREATE INDEX idx_s28_export_row_period ON s28_export_row (period, id);

-- ---------------------------------------------------------------------------------------------------
-- The export job. An aggregate, because it has invariants: it cannot succeed twice, cannot succeed
-- without an artifact, cannot be retried unless it failed, and cannot be finished by a worker whose
-- claim has been taken over. Everything in this table is one of those facts or the evidence for one.
--
-- What is NOT here: how far along it is. See s28_export_progress.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE s28_export_job (
  id               VARCHAR(64)  PRIMARY KEY,
  period           VARCHAR(7)   NOT NULL,
  format           VARCHAR(16)  NOT NULL,
  status           VARCHAR(16)  NOT NULL,
  attempt          INT          NOT NULL DEFAULT 0,
  -- The claim. lease_owner names who is running it, lease_until says until when the claim is good.
  -- The same three columns the library's own outbox and process-effect relays carry, for the same reason.
  lease_owner      VARCHAR(128),
  lease_until      TIMESTAMPTZ,
  -- A *request*, not a state: the worker acknowledges it at a chunk boundary. A cancellation that could
  -- be imposed from outside would produce CANCELLED jobs whose artifact exists.
  cancel_requested BOOLEAN      NOT NULL DEFAULT FALSE,
  artifact_path    VARCHAR(512),
  artifact_bytes   BIGINT,
  artifact_rows    BIGINT,
  failure          VARCHAR(1024),
  submitted_at     TIMESTAMPTZ  NOT NULL,
  started_at       TIMESTAMPTZ,
  finished_at      TIMESTAMPTZ,
  version          BIGINT       NOT NULL
);

-- What the claim scans: queued jobs oldest first, plus running jobs whose lease has lapsed.
CREATE INDEX idx_s28_export_job_claimable ON s28_export_job (status, lease_until, submitted_at);

-- ---------------------------------------------------------------------------------------------------
-- Progress. One row per job, overwritten in place, and note what is missing: no version column.
--
-- This table is the scenario's answer to "should the job's state be an aggregate". Its *lifecycle* is —
-- the invariants above are real. Its *progress* is not: nothing is decided by it, no rule reads it, and
-- a lost tick costs nobody anything. Routing it through the aggregate would put every tick behind the
-- optimistic-lock version, where it would collide with the one write that actually matters — the
-- cancellation. ProgressIsNotAnInvariantTest measures both halves of that.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE s28_export_progress (
  job_id     VARCHAR(64) PRIMARY KEY,
  rows_done  BIGINT      NOT NULL,
  rows_total BIGINT,
  updated_at TIMESTAMPTZ NOT NULL
);

-- ---------------------------------------------------------------------------------------------------
-- The import batch. An aggregate for the same reason the export job is one: completion is a rule.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE s28_import_batch (
  id              VARCHAR(64) PRIMARY KEY,
  declared_chunks INT         NOT NULL,
  status          VARCHAR(16) NOT NULL,
  accepted_rows   BIGINT      NOT NULL DEFAULT 0,
  failure         VARCHAR(1024),
  opened_at       TIMESTAMPTZ NOT NULL,
  completed_at    TIMESTAMPTZ,
  version         BIGINT      NOT NULL
);

-- ---------------------------------------------------------------------------------------------------
-- Chunk receipts, and the second table that is deliberately outside an aggregate.
--
-- A thousand-chunk upload would give the batch a thousand-element child collection, rewritten in full on
-- every save — quadratic, and for values that bear no invariant between them: chunk 7 arriving says
-- nothing about chunk 8. So the receipts live here, written by their own mapper, and the batch is told
-- the *tally* when somebody asks it to complete. Same shape as the erasure gate in S27: the aggregate
-- receives the count as an argument rather than owning the rows.
--
-- The primary key is what makes a re-sent chunk free: PUT the same chunk twice and the second is a no-op.
-- ---------------------------------------------------------------------------------------------------
CREATE TABLE s28_import_chunk (
  batch_id    VARCHAR(64) NOT NULL,
  chunk_no    INT         NOT NULL,
  checksum    VARCHAR(64) NOT NULL,
  row_count   INT         NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (batch_id, chunk_no)
);
