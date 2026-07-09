package com.acme.samples.s3.ordering.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("s3_ordering.customers")
public class CustomerPo {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private Long creditLimitMinor;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getCreditLimitMinor() { return creditLimitMinor; }
    public void setCreditLimitMinor(Long creditLimitMinor) { this.creditLimitMinor = creditLimitMinor; }
}
