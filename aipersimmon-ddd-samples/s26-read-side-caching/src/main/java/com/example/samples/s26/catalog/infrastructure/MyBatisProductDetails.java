package com.example.samples.s26.catalog.infrastructure;

import com.example.samples.s26.catalog.application.CacheTelemetry;
import com.example.samples.s26.catalog.application.ProductDetail;
import com.example.samples.s26.catalog.application.ProductDetails;
import com.example.samples.s26.catalog.application.SalesWindow;
import com.example.samples.s26.catalog.domain.Sku;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The slow path, and the only thing in this sample that counts how often it runs.
 *
 * <p>Counting here rather than in the interceptor is deliberate: a miss and a database read are not the
 * same event once single flight exists — ten misses on one cold key produce one read — and every claim this
 * sample makes about what caching bought is a comparison of those two numbers. A test that could only see
 * misses could not tell coalescing from luck.
 */
@Repository
class MyBatisProductDetails implements ProductDetails {

  private final ProductDetailMapper mapper;
  private final CacheTelemetry telemetry;

  MyBatisProductDetails(ProductDetailMapper mapper, CacheTelemetry telemetry) {
    this.mapper = mapper;
    this.telemetry = telemetry;
  }

  @Override
  public Optional<ProductDetail> of(Sku sku) {
    telemetry.databaseRead();
    ProductDetailRow row =
        mapper.select(sku.value(), Instant.now().minus(SalesWindow.RECENT));
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new ProductDetail(row.getSku(), row.getName(), row.getPriceCents(), row.getSoldRecently()));
  }
}
