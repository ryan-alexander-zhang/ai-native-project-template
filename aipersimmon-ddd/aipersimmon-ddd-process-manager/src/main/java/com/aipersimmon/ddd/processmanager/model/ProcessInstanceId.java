package com.aipersimmon.ddd.processmanager.model;

/**
 * The runtime-assigned identity of a single process instance. Created by the runtime
 * when a process starts (not by the consumer), it is the primary key of the instance
 * row and the anchor of its transitions, effects, and deadlines.
 *
 * @param value the instance id; non-blank
 */
public record ProcessInstanceId(String value) {

    public ProcessInstanceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("process instance id value required");
        }
    }
}
