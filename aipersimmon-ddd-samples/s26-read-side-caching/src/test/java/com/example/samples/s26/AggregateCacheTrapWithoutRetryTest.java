package com.example.samples.s26;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.aipersimmon.ddd.testsupport.RedisServiceConnection;
import com.example.samples.s26.catalog.domain.Products;
import com.example.samples.s26.catalog.domain.Sku;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Which half of the silent failure is the retry's doing.
 *
 * <p>{@link AggregateCacheTrapTest} shows a command reporting success while writing nothing. Two things were
 * involved: the memoised aggregate, and the retry that reran the command against it. Attributing the silence to
 * the retry would be a guess unless the retry is removed and the outcome measured — so here it is removed, and
 * the same sequence raises the conflict it should.
 *
 * <p>Which locates each part exactly. The memoisation is what makes the write impossible; the retry is what
 * makes it <em>quiet</em>. Neither is safe with the other, and a team that had disabled the retry would have a
 * 409 storm instead of a silent one — visible, wrong, and much easier to find.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "aipersimmon.ddd.cqrs.retry-on-conflict.enabled=false")
@Import({
  PostgresServiceConnection.class,
  RedisServiceConnection.class,
  ControllableCache.class,
  SlowReads.class,
  CachedProducts.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
class AggregateCacheTrapWithoutRetryTest extends CacheTestBase {

  @Autowired private Products products;
  @Autowired private CachedProducts.Memoising memoising;

  @BeforeEach
  void emptyTheAggregateCache() {
    memoising.clear();
  }

  @Test
  void withoutTheRetryTheConflictIsAtLeastVisible() {
    products.find(new Sku(KEYBOARD));

    jdbc.update(
        "UPDATE s26_product SET name = 'Moved On', version = version + 1 WHERE sku = ?", KEYBOARD);

    assertThatThrownBy(() -> rename(KEYBOARD, "Mechanical Keyboard"))
        .isInstanceOf(ConcurrencyConflictException.class);
  }
}
