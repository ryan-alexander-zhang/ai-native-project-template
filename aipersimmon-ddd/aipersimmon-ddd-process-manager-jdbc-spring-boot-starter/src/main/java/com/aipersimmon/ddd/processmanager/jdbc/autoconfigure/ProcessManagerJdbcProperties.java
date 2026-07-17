package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the JDBC Process Manager runtime, under
 * {@code aipersimmon.ddd.process-manager.jdbc} (design-00004 §5.4). {@link #validate()}
 * is called by the auto-configuration so illegal values fail fast at startup rather than
 * misbehaving at runtime.
 */
@ConfigurationProperties(prefix = "aipersimmon.ddd.process-manager.jdbc")
public class ProcessManagerJdbcProperties {

    /** Enable the JDBC runtime auto-configuration. */
    private boolean enabled = true;
    /** {@code auto} (probe the DataSource), or {@code postgresql} / {@code mysql} / {@code h2}. */
    private String dialect = "auto";
    /** {@code reject} or {@code fold}: a start for an existing business key with a new message id. */
    private String startDuplicateBusinessKey = "reject";
    /** The transaction-level retry limit for a revision conflict. */
    private int concurrencyMaxRetries = 3;
    /** Explicit node lease identity; a random one is generated when blank. Not a business identity. */
    private String workerId = "";
    /** {@code validate} (verify the tables exist) or {@code none}. Never creates tables. */
    private String schemaValidation = "validate";
    /** Time to wait for in-flight worker tasks after shutdown stops claiming. */
    private Duration shutdownTimeout = Duration.ofSeconds(30);

    private final Worker effectRelay = new Worker();
    private final Worker deadlineWorker = new Worker();
    private final Observability observability = new Observability();

    /** Thresholds for the health indicator and the stuck-instance SLI (design-00004 §5.3). */
    public static class Observability {
        /** An active instance idle (no pending work) longer than this counts as stuck. */
        private Duration stuckThreshold = Duration.ofMinutes(15);
        /** Oldest due-but-unhandled effect/deadline older than this reports health DEGRADED. */
        private Duration oldestPendingWarn = Duration.ofSeconds(60);

        public Duration getStuckThreshold() {
            return stuckThreshold;
        }

        public void setStuckThreshold(Duration stuckThreshold) {
            this.stuckThreshold = stuckThreshold;
        }

        public Duration getOldestPendingWarn() {
            return oldestPendingWarn;
        }

        public void setOldestPendingWarn(Duration oldestPendingWarn) {
            this.oldestPendingWarn = oldestPendingWarn;
        }

        void validate() {
            if (stuckThreshold == null || stuckThreshold.isNegative() || stuckThreshold.isZero()) {
                throw new IllegalStateException("observability.stuck-threshold must be positive");
            }
            if (oldestPendingWarn == null || oldestPendingWarn.isNegative() || oldestPendingWarn.isZero()) {
                throw new IllegalStateException("observability.oldest-pending-warn must be positive");
            }
        }
    }

    /** Effect-relay / deadline-worker polling, lease, and retry settings. */
    public static class Worker {
        private boolean enabled = true;
        private Duration pollDelay = Duration.ofMillis(500);
        private int batchSize = 100;
        private Duration leaseDuration = Duration.ofSeconds(30);
        private int maxAttempts = 12;
        private final Backoff backoff = new Backoff();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getPollDelay() {
            return pollDelay;
        }

        public void setPollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Backoff getBackoff() {
            return backoff;
        }

        void validate(String name) {
            if (pollDelay == null || pollDelay.isNegative() || pollDelay.isZero()) {
                throw new IllegalStateException(name + ".poll-delay must be positive");
            }
            if (batchSize < 1) {
                throw new IllegalStateException(name + ".batch-size must be >= 1");
            }
            if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
                throw new IllegalStateException(name + ".lease-duration must be positive");
            }
            if (maxAttempts < 1) {
                throw new IllegalStateException(name + ".max-attempts must be >= 1");
            }
            backoff.validate(name + ".backoff");
        }
    }

    /** Capped, jittered exponential backoff settings. */
    public static class Backoff {
        private Duration initial = Duration.ofSeconds(1);
        private Duration max = Duration.ofMinutes(5);
        private double multiplier = 2.0;
        private double jitter = 0.2;

        public Duration getInitial() {
            return initial;
        }

        public void setInitial(Duration initial) {
            this.initial = initial;
        }

        public Duration getMax() {
            return max;
        }

        public void setMax(Duration max) {
            this.max = max;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }

        void validate(String name) {
            if (initial == null || initial.isNegative() || initial.isZero()) {
                throw new IllegalStateException(name + ".initial must be positive");
            }
            if (max == null || max.compareTo(initial) < 0) {
                throw new IllegalStateException(name + ".max must be >= initial");
            }
            if (multiplier < 1.0) {
                throw new IllegalStateException(name + ".multiplier must be >= 1.0");
            }
            if (jitter < 0.0 || jitter > 1.0) {
                throw new IllegalStateException(name + ".jitter must be within [0, 1]");
            }
        }
    }

    /** Validate the whole configuration; called during auto-configuration. */
    public void validate() {
        if (concurrencyMaxRetries < 0) {
            throw new IllegalStateException("concurrency.max-retries must be >= 0");
        }
        if (!startDuplicateBusinessKey.equals("reject") && !startDuplicateBusinessKey.equals("fold")) {
            throw new IllegalStateException("start.duplicate-business-key must be 'reject' or 'fold'");
        }
        if (shutdownTimeout == null || shutdownTimeout.isNegative()) {
            throw new IllegalStateException("shutdown-timeout must be >= 0");
        }
        effectRelay.validate("effect-relay");
        deadlineWorker.validate("deadline-worker");
        observability.validate();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDialect() {
        return dialect;
    }

    public void setDialect(String dialect) {
        this.dialect = dialect;
    }

    public String getStartDuplicateBusinessKey() {
        return startDuplicateBusinessKey;
    }

    public void setStartDuplicateBusinessKey(String startDuplicateBusinessKey) {
        this.startDuplicateBusinessKey = startDuplicateBusinessKey;
    }

    public int getConcurrencyMaxRetries() {
        return concurrencyMaxRetries;
    }

    public void setConcurrencyMaxRetries(int concurrencyMaxRetries) {
        this.concurrencyMaxRetries = concurrencyMaxRetries;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getSchemaValidation() {
        return schemaValidation;
    }

    public void setSchemaValidation(String schemaValidation) {
        this.schemaValidation = schemaValidation;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public Worker getEffectRelay() {
        return effectRelay;
    }

    public Worker getDeadlineWorker() {
        return deadlineWorker;
    }

    public Observability getObservability() {
        return observability;
    }
}
