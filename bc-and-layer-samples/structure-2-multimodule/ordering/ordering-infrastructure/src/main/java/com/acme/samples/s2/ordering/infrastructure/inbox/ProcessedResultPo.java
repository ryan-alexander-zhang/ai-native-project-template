package com.acme.samples.s2.ordering.infrastructure.inbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** Inbox record: one row per order whose stock result has been applied (idempotency key). */
@TableName("s2_ordering.processed_result")
public class ProcessedResultPo {
    @TableId(type = IdType.INPUT)
    private String orderId;

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
}
