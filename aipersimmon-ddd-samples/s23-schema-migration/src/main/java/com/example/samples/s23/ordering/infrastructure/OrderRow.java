package com.example.samples.s23.ordering.infrastructure;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * The order row, after V4.
 *
 * <p>What is <em>absent</em> is the interesting part: there is no {@code shipTo} field. The column V1 created
 * and V3 dropped leaves no trace here, because a row class describes the table as it is — and a row class that
 * kept a retired field "just in case" would keep the old shape alive in the code long after the database
 * forgot it. During the deploy window between V2 and V3 this class would have carried all three fields, which
 * is the honest cost of expand/contract and lasts exactly as long as the window.
 */
@TableName("s23_order")
class OrderRow implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String customerId;

  private String sku;

  private Integer quantity;

  private String shipToStreet;

  private String shipToCity;

  /** Null for a row the backfill has not reached. */
  private String handling;

  @Version private Long version;

  String getId() {
    return id;
  }

  void setId(String id) {
    this.id = id;
  }

  String getCustomerId() {
    return customerId;
  }

  void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  String getSku() {
    return sku;
  }

  void setSku(String sku) {
    this.sku = sku;
  }

  Integer getQuantity() {
    return quantity;
  }

  void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  String getShipToStreet() {
    return shipToStreet;
  }

  void setShipToStreet(String shipToStreet) {
    this.shipToStreet = shipToStreet;
  }

  String getShipToCity() {
    return shipToCity;
  }

  void setShipToCity(String shipToCity) {
    this.shipToCity = shipToCity;
  }

  String getHandling() {
    return handling;
  }

  void setHandling(String handling) {
    this.handling = handling;
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
