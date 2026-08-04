package com.example.samples.s26;

import com.example.samples.s26.catalog.domain.Product;
import com.example.samples.s26.catalog.domain.Products;
import com.example.samples.s26.catalog.domain.Sku;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The mistake, wired up so its consequences can be measured. <strong>Test scope only.</strong>
 *
 * <p>It is four lines of memoisation over the write repository, and it is the single most tempting change in
 * the scenario: the aggregate is loaded on every command, the load is a database round trip, and a map makes
 * it disappear. Every read-side test in this sample passes with it installed. The architecture test forbids
 * it; this exists so that the prohibition is a demonstrated consequence rather than a rule with a comment.
 *
 * <p>The two failures it produces are not the ones people expect. It does not cause lost updates — optimistic
 * locking still refuses a write from a stale version, so the database stays consistent. What it does is
 * (1) share one mutable instance between callers, so an <em>abandoned</em> change becomes visible to the next
 * reader, and (2) make {@code version()} a fact about the cache rather than about the database, which turns a
 * recoverable conflict into an unrecoverable one — the retry reloads from the cache and gets the same stale
 * version, for ever.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CachedProducts {

  @Bean
  @Primary
  Memoising memoisingProducts(@Qualifier("myBatisProducts") Products delegate) {
    return new Memoising(delegate);
  }

  /** Hands out the same {@link Product} instance for the same sku, for as long as the process lives. */
  public static class Memoising implements Products {

    private final Products delegate;
    private final Map<String, Product> byId = new ConcurrentHashMap<>();

    Memoising(Products delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<Product> find(Sku sku) {
      Product cached = byId.get(sku.value());
      if (cached != null) {
        return Optional.of(cached);
      }
      Optional<Product> loaded = delegate.find(sku);
      loaded.ifPresent(product -> byId.put(sku.value(), product));
      return loaded;
    }

    @Override
    public void save(Product product) {
      delegate.save(product);
      // Refreshing the entry on save is the version of this mistake that looks most defensible: the cache now
      // holds the instance whose version the repository just advanced, so it agrees with the database — right
      // up until anybody else writes the same row.
      byId.put(product.id().value(), product);
    }

    /** Empty it, so a test can compare cached and uncached behaviour in the same context. */
    public void clear() {
      byId.clear();
    }
  }
}
