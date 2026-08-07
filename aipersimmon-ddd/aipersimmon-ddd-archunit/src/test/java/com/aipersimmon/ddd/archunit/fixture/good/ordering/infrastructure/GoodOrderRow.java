package com.aipersimmon.ddd.archunit.fixture.good.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * A mapped row, correctly placed: the table's shape, in the infrastructure layer, where the adapter
 * that maps it lives. The good path of {@code persistenceMappingsShouldStayInInfrastructure}.
 */
@TableName("ordering.orders")
public class GoodOrderRow {

  private String id;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }
}
