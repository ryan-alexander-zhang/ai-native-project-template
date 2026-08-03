package com.example.samples.s21.inventory.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** Stable identities for this context's refusals. */
public enum InventoryErrorCode implements ErrorCode {
  SKU_NOT_STOCKED("inventory.sku-not-stocked", ErrorCategory.NOT_FOUND),
  INSUFFICIENT_STOCK("inventory.insufficient-stock", ErrorCategory.CONFLICT);

  private final String code;
  private final ErrorCategory category;

  InventoryErrorCode(String code, ErrorCategory category) {
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
