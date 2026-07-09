package com.acme.samples.s2.inventory.infrastructure.messaging;

import com.acme.samples.s2.inventory.application.stock.Reservations;
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

    @Override
    public Optional<Reservation> find(String orderId) {
        ReservationPo po = reservationMapper.selectById(orderId);
        return po == null ? Optional.empty()
                : Optional.of(new Reservation(po.getSku(), po.getQty(), po.getOutcome()));
    }

    @Override
    public void markReleased(String orderId) {
        ReservationPo po = reservationMapper.selectById(orderId);
        if (po == null) return;
        po.setOutcome("RELEASED");
        reservationMapper.updateById(po);
    }
}
