package com.acme.samples.s1.inventory.infrastructure.persistence.stock;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("s1_inventory.stock_items")
public class StockItemPo {
    @TableId(type = IdType.INPUT)
    private String sku;
    private Integer available;

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public Integer getAvailable() { return available; }
    public void setAvailable(Integer available) { this.available = available; }
}
