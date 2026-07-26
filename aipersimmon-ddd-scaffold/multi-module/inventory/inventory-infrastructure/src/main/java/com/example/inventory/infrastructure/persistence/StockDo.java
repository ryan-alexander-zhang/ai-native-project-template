package com.example.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** MyBatis-Plus data object for an {@code inventory.stocks} row. */
@TableName("inventory.stocks")
public class StockDo {

  @TableId(type = IdType.INPUT)
  private String sku;

  private Integer available;

  /** Optimistic-lock version; see {@code OrderDo#version}. Guards against overselling a SKU. */
  @Version private Long version;

  public String getSku() {
    return sku;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public Integer getAvailable() {
    return available;
  }

  public void setAvailable(Integer available) {
    this.available = available;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }
}
