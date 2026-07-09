package com.acme.samples.s2.ordering.application;

import com.acme.samples.s2.ordering.domain.order.OrderStatus;
import com.acme.samples.s2.ordering.domain.order.Orders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies Inventory's decision to the order. */
@Service
public class ConfirmOrderService {

    private final Orders orders;

    public ConfirmOrderService(Orders orders) {
        this.orders = orders;
    }

    @Transactional
    public void apply(String orderId, boolean reserved) {
        orders.updateStatus(orderId, reserved ? OrderStatus.CONFIRMED : OrderStatus.CANCELLED);
    }
}
