package com.aipersimmon.ddd.web.store.jdbc;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link JdbcWebStoreCleanup} on its own single-thread scheduler.
 *
 * <p>Its own thread, not the application's shared scheduler, and not {@code @Scheduled}: a sweep is
 * a database round trip that can block, and the process manager's workers are arranged the same way
 * for the same reason. A failed sweep is logged and swallowed, because a scheduler thread that dies
 * on one bad batch stops sweeping forever and says nothing.
 */
public final class WebStoreCleanupScheduler implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(WebStoreCleanupScheduler.class);

  private final JdbcWebStoreCleanup cleanup;
  private final Duration pollDelay;

  private ScheduledExecutorService executor;
  private volatile boolean running;

  public WebStoreCleanupScheduler(JdbcWebStoreCleanup cleanup, Duration pollDelay) {
    this.cleanup = cleanup;
    this.pollDelay = pollDelay;
  }

  @Override
  public void start() {
    executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "aipersimmon-web-store-cleanup");
              thread.setDaemon(true);
              return thread;
            });
    long millis = pollDelay.toMillis();
    executor.scheduleWithFixedDelay(this::sweepQuietly, millis, millis, TimeUnit.MILLISECONDS);
    running = true;
  }

  private void sweepQuietly() {
    try {
      cleanup.sweep();
    } catch (RuntimeException e) {
      log.warn("Web-store cleanup sweep failed; will retry on the next run", e);
    }
  }

  @Override
  public void stop() {
    running = false;
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
