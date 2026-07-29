-- Make parked-input replay crash-safe by giving it a durable queue.
--
-- An input that arrives while an instance is SUSPENDED is parked as a transition row and owed
-- exactly one replay once the instance resumes. That debt used to live only in the call stack of
-- the operator's redrive: resume committed, then the replay ran outside any transaction, so a
-- crash (or an exception from the definition) between the two left the instance RUNNING with the
-- inputs parked forever and the broker long since acked — a silent, unrecoverable loss, with
-- nothing anywhere scanning to find it.
--
-- replayed_at turns the parked rows into a queue that survives the process: NULL means "still
-- owed", and the parked-input worker drains it, marking each row only after that replay's own
-- transaction committed. Crashing before the mark simply leaves the input in the queue, and the
-- replay is idempotent — UNIQUE(instance_id, input_message_id) on the replay transition makes a
-- second attempt a duplicate no-op. This is the only column of a transition row ever written
-- after insert, and it records the disposition of a parked input, not the decision the row logs.
ALTER TABLE aipersimmon_process_transition ADD COLUMN IF NOT EXISTS replayed_at TIMESTAMP;

-- The worker's work-list query: parked rows still owed, per instance. Narrow enough that the scan
-- cost stays flat as replayed history accumulates, which is the whole point of the marker.
CREATE INDEX IF NOT EXISTS idx_process_transition_parked
    ON aipersimmon_process_transition (transition_kind, replayed_at, instance_id);
