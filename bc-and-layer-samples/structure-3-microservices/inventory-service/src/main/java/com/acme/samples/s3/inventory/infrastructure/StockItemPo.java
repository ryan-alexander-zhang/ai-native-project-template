package com.acme.samples.s3.inventory.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("s3_inventory.stock_items")
public class StockItemPo {
    @TableId(type = IdType.INPUT)
    private String sku;
    private Integer available;

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public Integer getAvailable() { return available; }
    public void setAvailable(Integer available) { this.available = available; }
}
