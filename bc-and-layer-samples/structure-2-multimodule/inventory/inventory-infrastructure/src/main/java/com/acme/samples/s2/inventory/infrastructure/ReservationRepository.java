package com.acme.samples.s2.inventory.infrastructure;

import com.acme.samples.s2.inventory.application.Reservations;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ReservationRepository implements Reservations {

    private final ReservationMapper reservationMapper;

    public ReservationRepository(ReservationMapper reservationMapper) {
        this.reservationMapper = reservationMapper;
    }

    @Override
    public Optional<String> outcome(String orderId) {
        ReservationPo po = reservationMapper.selectById(orderId);
        return po == null ? Optional.empty() : Optional.of(po.getOutcome());
    }

    @Override
    public void record(String orderId, String sku, int qty, String outcome) {
        ReservationPo po = new ReservationPo();
        po.setOrderId(orderId);
        po.setSku(sku);
        po.setQty(qty);
        po.setOutcome(outcome);
        reservationMapper.insert(po);
    }
}
