package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.time.Instant;

/**
 * One scheduled deadline generation to persist as {@code PENDING}. A reschedule of the
 * same name uses a higher {@code generation}, so a late fire of an older generation is
 * a no-op. The encoded deadline input is delivered back as an
 * ordinary input when the timer fires.
 */
public record ProcessDeadlineInsert(
        String deadlineId,
        ProcessInstanceId instanceId,
        DeadlineName name,
        long generation,
        Instant dueAt,
        String inputType,
        int inputVersion,
        byte[] inputPayload,
        String correlationId,
        String causationId,
        String traceId,
        String traceparent,
        String traceState) {

    public ProcessDeadlineInsert {
        inputPayload = inputPayload.clone();
    }
}
