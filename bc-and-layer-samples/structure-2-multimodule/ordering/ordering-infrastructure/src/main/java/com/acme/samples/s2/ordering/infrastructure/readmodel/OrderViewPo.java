package com.acme.samples.s2.ordering.infrastructure.readmodel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** Persistence shape of the order read model (projection), separate from the write model. */
@TableName("s2_ordering.order_view")
public class OrderViewPo {
    @TableId(type = IdType.INPUT)
    private String orderId;
    private String status;
    private Long totalMinor;
    private String currency;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTotalMinor() { return totalMinor; }
    public void setTotalMinor(Long totalMinor) { this.totalMinor = totalMinor; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
}
