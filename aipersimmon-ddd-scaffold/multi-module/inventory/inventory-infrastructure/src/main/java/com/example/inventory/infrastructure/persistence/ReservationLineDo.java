package com.example.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;

/** MyBatis-Plus data object for one {@code inventory.reservation_lines} row (held qty per SKU). */
@TableName("inventory.reservation_lines")
public class ReservationLineDo {

    private String reservationId;
    private String sku;
    private Integer quantity;

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
