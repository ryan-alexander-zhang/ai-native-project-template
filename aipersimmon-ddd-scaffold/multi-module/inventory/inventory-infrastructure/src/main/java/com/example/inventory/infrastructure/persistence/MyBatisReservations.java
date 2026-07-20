package com.example.inventory.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.inventory.domain.stock.Reservation;
import com.example.inventory.domain.stock.ReservationId;
import com.example.inventory.domain.stock.Reservations;
import com.example.inventory.domain.stock.Sku;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed {@link Reservations}: the header in {@code inventory.reservations} and the
 * held-per-SKU quantities in {@code inventory.reservation_lines}. Runs in the command transaction on
 * the shared DataSource. The {@code released} flag is persisted so a reload knows whether the stock
 * was already handed back — the exactly-once release guarantee survives restarts.
 */
@Repository
public class MyBatisReservations implements Reservations {

    private final ReservationMapper reservations;
    private final ReservationLineMapper lines;

    public MyBatisReservations(ReservationMapper reservations, ReservationLineMapper lines) {
        this.reservations = reservations;
        this.lines = lines;
    }

    @Override
    public void save(Reservation reservation) {
        String id = reservation.id().value();
        ReservationDo header = new ReservationDo();
        header.setId(id);
        header.setOrderId(reservation.orderId());
        header.setReleased(reservation.isReleased());
        if (reservations.selectById(id) == null) {
            reservations.insert(header);
        } else {
            reservations.updateById(header);
        }

        lines.delete(new LambdaQueryWrapper<ReservationLineDo>().eq(ReservationLineDo::getReservationId, id));
        for (Map.Entry<Sku, Integer> held : reservation.held()) {
            ReservationLineDo row = new ReservationLineDo();
            row.setReservationId(id);
            row.setSku(held.getKey().value());
            row.setQuantity(held.getValue());
            lines.insert(row);
        }
    }

    @Override
    public Optional<Reservation> findById(ReservationId id) {
        ReservationDo header = reservations.selectById(id.value());
        if (header == null) {
            return Optional.empty();
        }
        List<ReservationLineDo> rows = lines.selectList(
                new LambdaQueryWrapper<ReservationLineDo>().eq(ReservationLineDo::getReservationId, id.value()));
        Map<Sku, Integer> held = new LinkedHashMap<>();
        for (ReservationLineDo row : rows) {
            held.put(new Sku(row.getSku()), row.getQuantity());
        }
        Reservation reservation = new Reservation(id, header.getOrderId(), held);
        if (Boolean.TRUE.equals(header.getReleased())) {
            reservation.markReleased();
        }
        return Optional.of(reservation);
    }
}
