package com.example.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** MyBatis-Plus data object for an {@code inventory.stocks} row. */
@TableName("inventory.stocks")
public class StockDo {

    @TableId(type = IdType.INPUT)
    private String sku;
    private Integer available;

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
}
