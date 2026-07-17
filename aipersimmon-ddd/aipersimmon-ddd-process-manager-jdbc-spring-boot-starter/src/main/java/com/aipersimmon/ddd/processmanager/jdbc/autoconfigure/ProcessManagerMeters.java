package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

/** Metric names for the Process Manager SLIs, under a common prefix. */
final class ProcessManagerMeters {

    private static final String PREFIX = "aipersimmon.process.manager.";

    static final String OLDEST_PENDING_EFFECT_AGE = PREFIX + "oldest.pending.effect.age";
    static final String OLDEST_PENDING_DEADLINE_AGE = PREFIX + "oldest.pending.deadline.age";
    static final String DEAD_EFFECTS = PREFIX + "dead.effects";
    static final String DEAD_DEADLINES = PREFIX + "dead.deadlines";
    static final String SUSPENDED_INSTANCES = PREFIX + "suspended.instances";
    static final String STUCK_INSTANCES = PREFIX + "stuck.instances";
    static final String CLAIM_LATENCY = PREFIX + "claim.latency";
    static final String DISPATCH_LATENCY = PREFIX + "dispatch.latency";
    static final String ADVANCE_CONFLICT_RETRIES = PREFIX + "advance.conflict.retries";

    private ProcessManagerMeters() {
    }
}
