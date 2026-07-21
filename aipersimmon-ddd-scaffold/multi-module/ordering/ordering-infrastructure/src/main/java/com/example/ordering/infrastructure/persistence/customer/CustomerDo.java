package com.example.ordering.infrastructure.persistence.customer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** MyBatis-Plus data object for a {@code ordering.customers} row. */
@TableName("ordering.customers")
public class CustomerDo {

  @TableId(type = IdType.INPUT)
  private String id;

  private String name;
  private Long creditMinor;
  private String currency;

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
}
