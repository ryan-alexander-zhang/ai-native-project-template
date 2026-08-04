package com.example.samples.s26.catalog.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** This context's refusals. */
public enum CatalogErrorCode implements ErrorCode {
  PRODUCT_NOT_FOUND("catalog.product-not-found", ErrorCategory.NOT_FOUND);

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
