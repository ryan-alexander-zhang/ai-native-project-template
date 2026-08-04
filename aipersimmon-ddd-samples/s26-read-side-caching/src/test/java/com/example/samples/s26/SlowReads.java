package com.example.samples.s26;

import com.example.samples.s26.catalog.application.ProductDetail;
import com.example.samples.s26.catalog.application.ProductDetails;
import com.example.samples.s26.catalog.domain.Sku;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The real read, made slow enough for a stampede to be observable.
 *
 * <p>A stampede is a race, and a race whose window is a fast local query is a race that mostly does not
 * happen — so a test that fired ten threads at a cold key and counted one read would prove nothing about
 * single flight. This holds the read open until either every expected caller has arrived or a short timeout
 * passes, which makes both outcomes deterministic: without single flight all N arrive and are released
 * immediately; with it exactly one arrives and is released by the timeout.
 *
 * <p>It counts arrivals itself rather than trusting the telemetry, so the two numbers can disagree if
 * something is miswired.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SlowReads {

  @Bean
  @Primary
  Gated gatedProductDetails(@Qualifier("myBatisProductDetails") ProductDetails delegate) {
    return new Gated(delegate);
  }

  /** Blocks each caller until {@code expected} of them are inside, or {@code holdMillis} elapses. */
  public static class Gated implements ProductDetails {

    private final ProductDetails delegate;
    private final AtomicInteger inside = new AtomicInteger();

    private volatile int expected;
    private volatile long holdMillis;
    private volatile CountDownLatch everyoneArrived = new CountDownLatch(0);

    Gated(ProductDetails delegate) {
      this.delegate = delegate;
    }

    /** Arm the gate for {@code expected} concurrent callers, releasing after {@code holdMillis} regardless. */
    public void expect(int expected, long holdMillis) {
      this.expected = expected;
      this.holdMillis = holdMillis;
      this.inside.set(0);
      this.everyoneArrived = new CountDownLatch(expected);
    }

    /** Disarm. */
    public void reset() {
      expect(0, 0);
    }

    /** How many callers actually reached the source. */
    public int arrivals() {
      return inside.get();
    }

    @Override
    public Optional<ProductDetail> of(Sku sku) {
      if (expected > 0) {
        inside.incrementAndGet();
        everyoneArrived.countDown();
        try {
          everyoneArrived.await(holdMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      return delegate.of(sku);
    }
  }
}
