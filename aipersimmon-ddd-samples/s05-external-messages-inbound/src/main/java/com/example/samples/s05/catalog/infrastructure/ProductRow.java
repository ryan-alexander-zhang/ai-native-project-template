package com.example.samples.s05.catalog.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * The product row.
 *
 * <p>Two versions live here and they are not the same thing: {@code version} is this row's optimistic
 * lock (two of our own threads racing), while {@code upstream_revision} is the ERP's ordering token (two
 * of their messages racing). Conflating them would make a replay look like a concurrency conflict.
 */
@TableName("s05_product")
class ProductRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String sku;

  private String name;

  private Long priceCents;

  private Long upstreamRevision;

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

  Long getUpstreamRevision() {
    return upstreamRevision;
  }

  void setUpstreamRevision(Long upstreamRevision) {
    this.upstreamRevision = upstreamRevision;
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
