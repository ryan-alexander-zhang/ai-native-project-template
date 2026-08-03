package com.example.samples.s05.catalog.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** Stable identities for this context's refusals. */
public enum CatalogErrorCode implements ErrorCode {
  NEGATIVE_PRICE("catalog.negative-price", ErrorCategory.DOMAIN_RULE),
  PRODUCT_NOT_MIRRORED("catalog.product-not-mirrored", ErrorCategory.NOT_FOUND);

  private final String code;
  private final ErrorCategory category;

  CatalogErrorCode(String code, ErrorCategory category) {
    this.code = code;
    this.category = category;
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public ErrorCategory category() {
    return category;
  }
}
