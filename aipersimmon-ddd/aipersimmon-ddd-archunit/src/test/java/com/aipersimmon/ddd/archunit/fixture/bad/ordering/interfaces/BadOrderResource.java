package com.aipersimmon.ddd.archunit.fixture.bad.ordering.interfaces;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodStockItem;
import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodStockItems;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Violates two of the rules at once, which is how this shape actually turns up: an endpoint that
 * holds a repository port ({@code portsShouldNotBeUsedByInboundAdapters}) and answers with the
 * aggregate it loaded ({@code controllerSignaturesShouldNotExposeTheDomain}).
 *
 * <p>It also sits in {@code ..interfaces..} rather than {@code ..adapter..} on purpose: both rules
 * read {@link com.aipersimmon.ddd.archunit.Layers}, and a fixture under the other spelling is what
 * measures that they do.
 */
@RestController
public class BadOrderResource {

  private final GoodStockItems stockItems;

  public BadOrderResource(GoodStockItems stockItems) {
    this.stockItems = stockItems;
  }

  @GetMapping("/stock/{sku}")
  public GoodStockItem stock(String sku) {
    return stockItems.findBySku(sku).orElse(null);
  }
}
