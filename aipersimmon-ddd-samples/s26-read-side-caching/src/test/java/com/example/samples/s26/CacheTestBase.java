package com.example.samples.s26;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.QueryBus;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.testsupport.PostgresServiceConnection;
import com.aipersimmon.ddd.testsupport.RedisServiceConnection;
import com.example.samples.s26.catalog.application.AddProduct;
import com.example.samples.s26.catalog.application.CacheKeys;
import com.example.samples.s26.catalog.application.CacheSettings;
import com.example.samples.s26.catalog.application.CacheTelemetry;
import com.example.samples.s26.catalog.application.ProductDetail;
import com.example.samples.s26.catalog.application.ProductDetailQuery;
import com.example.samples.s26.catalog.application.QueryCache;
import com.example.samples.s26.catalog.application.RecordSale;
import com.example.samples.s26.catalog.application.RenameProduct;
import com.example.samples.s26.catalog.application.RepriceProduct;
import com.example.samples.s26.catalog.application.SalesBoard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One PostgreSQL, one Redis, and a clean slate before every test.
 *
 * <p>A real Redis rather than an in-memory stand-in, and not for realism's sake: the two things this sample
 * is most careful about — that every entry has a TTL, and that a tenant-wide flush scans rather than
 * enumerates — are properties of Redis and of the client, and a map would assert them into existence
 * without ever running them.
 *
 * <p>The reset flushes the cache through the public sweep rather than {@code FLUSHDB}, so every test also
 * exercises the scan path a hundred or so times over the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({
  PostgresServiceConnection.class,
  RedisServiceConnection.class,
  ControllableCache.class,
  SlowReads.class
})
@EnabledIf("com.aipersimmon.ddd.testsupport.DockerAvailable#dockerAvailable")
abstract class CacheTestBase {

  protected static final String KEYBOARD = "sku-keyboard";
  protected static final String MOUSE = "sku-mouse";
  protected static final String MONITOR = "sku-monitor";

  @Autowired protected CommandBus commandBus;
  @Autowired protected QueryBus queryBus;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected QueryCache cache;
  @Autowired protected CacheTelemetry telemetry;
  @Autowired protected CacheSettings settings;
  @Autowired protected SalesBoard salesBoard;
  @Autowired protected ControllableCache.Controlled controlledCache;
  @Autowired protected SlowReads.Gated gatedReads;

  @BeforeEach
  void freshEverything() {
    jdbc.update("DELETE FROM s26_product_sales");
    jdbc.update("DELETE FROM s26_order_line");
    jdbc.update("DELETE FROM s26_product");
    cache.evictMatching(CacheKeys.PREFIX + "*");
    controlledCache.reset();
    gatedReads.reset();
    TenantContext.clear();
    telemetry.reset();

    add(KEYBOARD, "Keyboard", 4500);
    add(MOUSE, "Mouse", 2500);
    add(MONITOR, "Monitor", 19900);
    // The counters above include the three creates' work; the tests measure from zero.
    telemetry.reset();
  }

  protected void add(String sku, String name, long priceCents) {
    commandBus.send(new AddProduct(sku, name, priceCents));
  }

  protected ProductDetail detail(String sku) {
    return queryBus.ask(new ProductDetailQuery(sku));
  }

  protected void rename(String sku, String name) {
    commandBus.send(new RenameProduct(sku, name));
  }

  protected void reprice(String sku, long priceCents) {
    commandBus.send(new RepriceProduct(sku, priceCents));
  }

  protected void sell(String sku, int quantity) {
    commandBus.send(new RecordSale(sku, quantity));
  }

  /** The key the interceptor would use for this product, under the currently bound tenant. */
  protected String keyOf(String sku) {
    return CacheKeys.current(new ProductDetailQuery(sku).cacheKey());
  }

  /** The name in the database, whatever the cache thinks. */
  protected String storedName(String sku) {
    return jdbc.queryForObject("SELECT name FROM s26_product WHERE sku = ?", String.class, sku);
  }

  /** Change the row without going through a command, so nothing is evicted. */
  protected void renameBehindTheCachesBack(String sku, String name) {
    jdbc.update("UPDATE s26_product SET name = ?, version = version + 1 WHERE sku = ?", name, sku);
  }
}
