-- Drop the redundant home-grown trace_id column: distributed-trace identity now travels in
-- the OpenTelemetry context (traceparent), and correlation_id carries the causal flow, so the
-- bare trace_id string is superseded on both the outbox and dead-letter tables.
ALTER TABLE aipersimmon_outbox DROP COLUMN IF EXISTS trace_id;
ALTER TABLE aipersimmon_dead_letter DROP COLUMN IF EXISTS trace_id;
