package com.example.samples.s22.inventory.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** Stable identities for this context's refusals. */
public enum InventoryErrorCode implements ErrorCode {
  NOT_ENOUGH_STOCK("inventory.not-enough-stock", ErrorCategory.DOMAIN_RULE),
  SKU_NOT_STOCKED("inventory.sku-not-stocked", ErrorCategory.NOT_FOUND);

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
