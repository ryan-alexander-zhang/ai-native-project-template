package com.example.samples.s08.inventory.domain;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;

/** The inventory context's error codes. */
public enum InventoryErrorCode implements ErrorCode {
  SKU_NOT_STOCKED("inventory.sku-not-stocked", ErrorCategory.NOT_FOUND),
  NOT_ENOUGH_STOCK("inventory.not-enough-stock", ErrorCategory.DOMAIN_RULE),
  BUDGET_EXCEEDED("inventory.reservation-budget-exceeded", ErrorCategory.DOMAIN_RULE);

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
