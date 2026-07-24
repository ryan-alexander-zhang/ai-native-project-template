package com.aipersimmon.ddd.tenancy.mybatisplus;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** A stand-in consumer domain entity for the tenant-line interceptor integration test. */
@TableName("t18_thing")
public class ThingEntity {

  @TableId(type = IdType.INPUT)
  private String id;

  private String tenantId;
  private String name;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
