-- Record where each event is going, decided in the writing transaction (PostgreSQL).
--
-- The destination used to be resolved at dispatch time from the event's @Externalized annotation.
-- An event written while it was externalized, whose route had since disappeared -- a version bump
-- that keeps the v1 class but drops the annotation, or a rolling deploy -- fell through to the
-- in-process leg and was marked sent: never reached the broker, no exception, no dead letter, no
-- consumer lag to alert on. Storing it makes the destination part of the same durable decision as
-- the payload and the type.
--
-- NULL means in-process, which is the default reach for an event with no @Externalized. Non-null is
-- the resolved target (a Kafka topic).
ALTER TABLE aipersimmon_outbox ADD COLUMN IF NOT EXISTS destination VARCHAR(255);

-- The dead-letter table needs it too, because a replay copies the row back into the outbox: without
-- the column, replaying an externalized event would resurrect it as in-process -- the same silent
-- loss, through a second door.
ALTER TABLE aipersimmon_dead_letter ADD COLUMN IF NOT EXISTS destination VARCHAR(255);
