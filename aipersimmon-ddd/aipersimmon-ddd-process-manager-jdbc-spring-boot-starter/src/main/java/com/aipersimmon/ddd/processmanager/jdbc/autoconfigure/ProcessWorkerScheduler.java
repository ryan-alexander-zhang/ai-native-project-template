package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import com.aipersimmon.ddd.processmanager.jdbc.deadline.JdbcProcessDeadlineWorker;
import com.aipersimmon.ddd.processmanager.jdbc.relay.JdbcProcessEffectRelay;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Drives the effect relay and deadline worker in the background, each on its own
 * single-thread scheduler (never a shared one, per design-00004 §5.5). A poll's failure
 * is logged and swallowed so a bad batch never kills the scheduler thread; on shutdown it
 * stops claiming and waits up to the configured graceful timeout for in-flight polls.
 * Multi-instance safety comes from the lease in the claim, not from this scheduler.
 */
public final class ProcessWorkerScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ProcessWorkerScheduler.class);

    private final JdbcProcessEffectRelay effectRelay;
    private final Duration effectPollDelay;
    private final JdbcProcessDeadlineWorker deadlineWorker;
    private final Duration deadlinePollDelay;
    private final Duration shutdownTimeout;

    private final List<ScheduledExecutorService> executors = new ArrayList<>();
    private volatile boolean running;

    public ProcessWorkerScheduler(
            JdbcProcessEffectRelay effectRelay, Duration effectPollDelay,
            JdbcProcessDeadlineWorker deadlineWorker, Duration deadlinePollDelay,
            Duration shutdownTimeout) {
        this.effectRelay = effectRelay;
        this.effectPollDelay = effectPollDelay;
        this.deadlineWorker = deadlineWorker;
        this.deadlinePollDelay = deadlinePollDelay;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (effectRelay != null) {
            schedule("process-effect-relay", effectPollDelay, effectRelay::pollOnce);
        }
        if (deadlineWorker != null) {
            schedule("process-deadline-worker", deadlinePollDelay, deadlineWorker::pollOnce);
        }
        running = true;
    }

    private void schedule(String name, Duration pollDelay, IntSupplier poll) {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(threads);
        long delayMs = Math.max(1L, pollDelay.toMillis());
        executor.scheduleWithFixedDelay(() -> {
            try {
                poll.getAsInt();
            } catch (Throwable failure) {
                log.warn("{} poll failed; will retry on the next tick", name, failure);
            }
        }, delayMs, delayMs, TimeUnit.MILLISECONDS);
        executors.add(executor);
    }

    @Override
    public synchronized void stop() {
        running = false;
        executors.forEach(ScheduledExecutorService::shutdown);
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        for (ScheduledExecutorService executor : executors) {
            try {
                long remaining = deadline - System.nanoTime();
                executor.awaitTermination(Math.max(0L, remaining), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executors.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
