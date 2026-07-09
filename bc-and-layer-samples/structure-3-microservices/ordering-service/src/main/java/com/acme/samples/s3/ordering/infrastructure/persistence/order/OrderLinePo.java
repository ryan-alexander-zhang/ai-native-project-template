package com.acme.samples.s3.ordering.infrastructure.persistence.order;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("s3_ordering.order_lines")
public class OrderLinePo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderId;
    private String sku;
    private Integer qty;
    private Long unitPriceMinor;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public Integer getQty() { return qty; }
    public void setQty(Integer qty) { this.qty = qty; }
    public Long getUnitPriceMinor() { return unitPriceMinor; }
    public void setUnitPriceMinor(Long unitPriceMinor) { this.unitPriceMinor = unitPriceMinor; }
}
