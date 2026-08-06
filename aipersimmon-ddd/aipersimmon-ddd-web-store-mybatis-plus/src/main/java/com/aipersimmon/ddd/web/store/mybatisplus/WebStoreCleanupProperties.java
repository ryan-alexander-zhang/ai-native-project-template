package com.aipersimmon.ddd.web.store.mybatisplus;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the periodic sweep of expired web-store rows. */
@ConfigurationProperties(prefix = "aipersimmon.ddd.web.store.cleanup")
public class WebStoreCleanupProperties {

  /**
   * Whether expired rows are swept periodically.
   *
   * <p>On by default, unlike the process manager's retention: these rows carry an {@code
   * expires_at} the store itself wrote, and the stores already delete such rows opportunistically.
   * Switching this off means the tables grow without bound.
   */
  private boolean enabled = true;

  /**
   * How long between sweeps. Unhurried on purpose — expired rows cost storage, not correctness, and
   * the first sweep over a database that has been accumulating is the only large one.
   */
  private Duration pollDelay = Duration.ofHours(1);

  /**
   * How long a rate-limit counter is kept after its window began.
   *
   * <p>Must be longer than the longest {@code window} any rate-limit policy uses; the row does not
   * record which policy it was counting for, so this is the only thing keeping the sweep off live
   * counters. Getting it wrong resets a bucket's quota rather than failing a request.
   */
  private Duration rateLimitRetention = Duration.ofHours(24);

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

  public Duration getRateLimitRetention() {
    return rateLimitRetention;
  }

  public void setRateLimitRetention(Duration rateLimitRetention) {
    this.rateLimitRetention = rateLimitRetention;
  }
}
