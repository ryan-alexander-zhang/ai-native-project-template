package com.example.samples.s26.catalog.application;

import com.example.samples.s26.catalog.domain.Sku;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Ask the cache and the database the same question, and report when they disagree.
 *
 * <p>This is the answer to "how would anyone know the cache is wrong", and the reason it needs an answer
 * is that <strong>every other signal points the wrong way.</strong> A cache that has stopped being
 * invalidated does not fail, does not slow down, and does not log; its hit ratio <em>improves</em>,
 * because entries stop being dropped. Latency improves too. From the outside, a cache serving month-old
 * prices looks like a cache that is finally working.
 *
 * <p>So the check has to be deliberate: read the entry, compute the truth, compare. In production this
 * runs as a sampled background job over a small share of keys — enough to notice a systematic failure,
 * cheap enough that it is not itself the load — and its output is one number with an alert on it. Here it
 * is a method an operator (and a test) can call for one sku.
 *
 * <p>Note that a divergence is not automatically a defect: the sales figure in a detail is <em>allowed</em>
 * to be stale for up to a TTL, by the decision recorded in {@link ProductDetail}. So the report says what
 * differs and leaves the judgement out — a comparison that pretended to know which differences were
 * legitimate would either cry wolf on every sale or need to encode the staleness policy a second time.
 * What an operator watches for is a divergence in {@code name} or {@code priceCents}, which nothing is
 * allowed to leave stale.
 */
@Service
public class CacheAudit {

  private final QueryCache cache;
  private final ProductDetails details;
  private final ObjectMapper json;
  private final CacheTelemetry telemetry;

  CacheAudit(
      QueryCache cache,
      ProductDetails details,
      ObjectMapper json,
      CacheTelemetry telemetry) {
    this.cache = cache;
    this.details = details;
    this.json = json;
    this.telemetry = telemetry;
  }

  /** What the cache says versus what the database says, when they are not the same thing. */
  public record Divergence(String sku, ProductDetail cached, ProductDetail actual) {}

  /**
   * Compare one product.
   *
   * @return empty when there is no entry, or the entry agrees with the database
   */
  public Optional<Divergence> check(Sku sku) {
    String key = CacheKeys.current(new ProductDetailQuery(sku.value()).cacheKey());
    Optional<String> stored = cache.get(key);
    if (stored.isEmpty()) {
      // Nothing cached is not a divergence. A cache with no entry is a cache that is about to be right.
      return Optional.empty();
    }
    ProductDetail cached;
    try {
      cached = json.readValue(stored.get(), ProductDetail.class);
    } catch (Exception unreadable) {
      // An unreadable entry is a divergence of the worst kind — it is not even comparable — so it is
      // counted rather than ignored, and reported with a null on the cached side.
      telemetry.diverged();
      return Optional.of(new Divergence(sku.value(), null, details.of(sku).orElse(null)));
    }
    ProductDetail actual = details.of(sku).orElse(null);
    if (cached.equals(actual)) {
      return Optional.empty();
    }
    telemetry.diverged();
    return Optional.of(new Divergence(sku.value(), cached, actual));
  }
}
