-- Drop the redundant home-grown trace_id column: distributed-trace identity now travels in
-- the OpenTelemetry context (traceparent, persisted on effect/deadline rows for the durable
-- store-and-forward hop), and correlation_id carries the causal flow, so the bare trace_id
-- string is superseded on the transition, effect, and deadline tables.
ALTER TABLE aipersimmon_process_transition DROP COLUMN IF EXISTS trace_id;
ALTER TABLE aipersimmon_process_effect DROP COLUMN IF EXISTS trace_id;
ALTER TABLE aipersimmon_process_deadline DROP COLUMN IF EXISTS trace_id;
