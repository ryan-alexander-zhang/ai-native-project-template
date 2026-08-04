package com.example.samples.s26.catalog.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * The product row.
 *
 * <p>{@code @Version} is what the optimistic-locker interceptor keys on, and the library's repository base
 * refuses to continue if its rewrite did not happen. That guard is the reason the aggregate-cache trap in
 * this sample fails the way it does rather than silently: the version predicate <em>is</em> applied — it is
 * just applied with a number the cache invented.
 */
@TableName("s26_product")
class ProductRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String sku;

  private String name;

  private Long priceCents;

  @Version private Long version;

  String getSku() {
    return sku;
  }

  void setSku(String sku) {
    this.sku = sku;
  }

  String getName() {
    return name;
  }

  void setName(String name) {
    this.name = name;
  }

  Long getPriceCents() {
    return priceCents;
  }

  void setPriceCents(Long priceCents) {
    this.priceCents = priceCents;
  }

  @Override
  public Long getVersion() {
    return version;
  }

  @Override
  public void setVersion(Long version) {
    this.version = version;
  }
}
