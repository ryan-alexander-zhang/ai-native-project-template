package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s26.catalog.domain.CatalogErrorCode;
import com.example.samples.s26.catalog.domain.Sku;
import org.springframework.stereotype.Component;

/**
 * Answers from the source, and knows nothing about the cache.
 *
 * <p>That ignorance is the payoff of putting the cache on the interceptor. This handler is the same code
 * it would be if caching had never been considered, which means it can be read, tested and changed
 * without holding the cache in mind — and the cache can be switched off by removing one bean, with no
 * edit here at all.
 *
 * <p>The refusal is worth one line of thought: a missing product throws rather than returning empty, and
 * the interceptor never sees a value to store, so <em>not-found is not cached</em>. That is the right
 * default (see {@code CachingQueryInterceptor}), and it is also why an exception is the better shape
 * here than an {@code Optional} — a value the cache must not keep should not look like a value.
 */
@Component
class ProductDetailHandler implements QueryHandler<ProductDetailQuery, ProductDetail> {

  private final ProductDetails details;

  ProductDetailHandler(ProductDetails details) {
    this.details = details;
  }

  @Override
  public ProductDetail handle(ProductDetailQuery query) {
    return details
        .of(new Sku(query.sku()))
        .orElseThrow(
            () ->
                new EntityNotFoundException(
                    CatalogErrorCode.PRODUCT_NOT_FOUND, "no product " + query.sku()));
  }
}
