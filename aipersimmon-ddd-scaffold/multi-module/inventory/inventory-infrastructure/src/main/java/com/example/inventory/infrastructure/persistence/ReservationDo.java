package com.example.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** MyBatis-Plus data object for the {@code inventory.reservations} header row. */
@TableName("inventory.reservations")
public class ReservationDo {

    @TableId(type = IdType.INPUT)
    private String id;
    private String orderId;
    private Boolean released;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Boolean getReleased() {
        return released;
    }

    public void setReleased(Boolean released) {
        this.released = released;
    }
}
