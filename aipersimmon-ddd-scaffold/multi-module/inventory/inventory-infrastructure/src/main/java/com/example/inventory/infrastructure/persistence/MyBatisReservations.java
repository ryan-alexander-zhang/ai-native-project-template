package com.example.inventory.infrastructure.persistence;

import com.aipersimmon.ddd.application.DomainEvents;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.inventory.domain.stock.Reservation;
import com.example.inventory.domain.stock.ReservationId;
import com.example.inventory.domain.stock.Reservations;
import com.example.inventory.domain.stock.Sku;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed {@link Reservations}: the header in {@code inventory.reservations} and the
 * held-per-SKU quantities in {@code inventory.reservation_lines}. Runs in the command transaction
 * on the shared DataSource. The {@code released} flag is persisted so a reload knows whether the
 * stock was already handed back — the exactly-once release guarantee survives restarts.
 */
@Repository
public class MyBatisReservations implements Reservations {

  private final ReservationMapper reservations;
  private final ReservationLineMapper lines;
  private final DomainEvents domainEvents;

  public MyBatisReservations(
      ReservationMapper reservations, ReservationLineMapper lines, DomainEvents domainEvents) {
    this.reservations = reservations;
    this.lines = lines;
    this.domainEvents = domainEvents;
  }

  @Override
  public void save(Reservation reservation) {
    String id = reservation.id().value();
    ReservationDo header = new ReservationDo();
    header.setId(id);
    header.setOrderId(reservation.orderId());
    header.setReleased(reservation.isReleased());
    if (reservation.version() == 0) {
      header.setVersion(1L);
      reservations.insert(header);
    } else {
      header.setVersion(reservation.version());
      if (reservations.updateById(header) == 0) {
        throw new OptimisticLockingFailureException(
            "reservation " + id + " was modified concurrently");
      }
    }
    reservation.versionAdvanced();

    lines.delete(
        new LambdaQueryWrapper<ReservationLineDo>().eq(ReservationLineDo::getReservationId, id));
    for (Map.Entry<Sku, Integer> held : reservation.held()) {
      ReservationLineDo row = new ReservationLineDo();
      row.setReservationId(id);
      row.setSku(held.getKey().value());
      row.setQuantity(held.getValue());
      lines.insert(row);
    }

    domainEvents.publishAndClear(reservation);
  }

  @Override
  public Optional<Reservation> findById(ReservationId id) {
    ReservationDo header = reservations.selectById(id.value());
    if (header == null) {
      return Optional.empty();
    }
    List<ReservationLineDo> rows =
        lines.selectList(
            new LambdaQueryWrapper<ReservationLineDo>()
                .eq(ReservationLineDo::getReservationId, id.value()));
    Map<Sku, Integer> held = new LinkedHashMap<>();
    for (ReservationLineDo row : rows) {
      held.put(new Sku(row.getSku()), row.getQuantity());
    }
    return Optional.of(
        Reservation.reconstitute(
            id,
            header.getOrderId(),
            held,
            Boolean.TRUE.equals(header.getReleased()),
            header.getVersion()));
  }
}
