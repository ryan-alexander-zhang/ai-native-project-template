package com.example.ordering.infrastructure.persistence.customer;

import com.aipersimmon.ddd.persistence.mybatisplus.VersionedRow;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

/** MyBatis-Plus data object for a {@code ordering.customers} row. */
@TableName("ordering.customers")
public class CustomerDo implements VersionedRow {

  @TableId(type = IdType.INPUT)
  private String id;

  private String name;
  private Long creditMinor;
  private String currency;
  private Long usedMinor;

  /**
   * Optimistic-lock version; see {@code OrderDo#version}. Guards the credit limit against two
   * concurrent placements committing against one snapshot. {@code V3} deliberately left this table
   * unversioned while nothing wrote to it; {@code V5} adds the column now that something does.
   */
  @Version private Long version;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Long getCreditMinor() {
    return creditMinor;
  }

  public void setCreditMinor(Long creditMinor) {
    this.creditMinor = creditMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public Long getUsedMinor() {
    return usedMinor;
  }

  public void setUsedMinor(Long usedMinor) {
    this.usedMinor = usedMinor;
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
