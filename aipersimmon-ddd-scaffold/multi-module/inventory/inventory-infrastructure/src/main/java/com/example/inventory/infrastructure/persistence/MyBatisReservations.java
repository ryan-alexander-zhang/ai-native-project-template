package com.example.inventory.infrastructure.persistence;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.inventory.domain.stock.Reservation;
import com.example.inventory.domain.stock.ReservationId;
import com.example.inventory.domain.stock.Reservations;
import com.example.inventory.domain.stock.Sku;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed {@link Reservations}: the header in {@code inventory.reservations} and the
 * held-per-SKU quantities in {@code inventory.reservation_lines}. Runs in the command transaction
 * on the shared DataSource. The {@code released} flag is persisted so a reload knows whether the
 * stock was already handed back — the exactly-once release guarantee survives restarts.
 */
@Repository
public class MyBatisReservations extends MybatisPlusAggregateRepository<Reservation, ReservationDo>
    implements Reservations {

  private final ReservationMapper reservations;
  private final ReservationLineMapper lines;

  public MyBatisReservations(
      ReservationMapper reservations, ReservationLineMapper lines, DomainEvents domainEvents) {
    super(reservations, domainEvents);
    this.reservations = reservations;
    this.lines = lines;
  }

  @Override
  public void save(Reservation reservation) {
    saveAggregate(reservation);
  }

  @Override
  protected ReservationDo toRow(Reservation reservation) {
    ReservationDo header = new ReservationDo();
    header.setId(reservation.id().value());
    header.setOrderId(reservation.orderId());
    header.setReleased(reservation.isReleased());
    return header;
  }

  /**
   * Writes the held quantities, and only when the aggregate says they changed.
   *
   * <p>A release changes {@code released} and nothing else, so rewriting every held row on that
   * save deleted and re-inserted the rows already there — pure cost, scaling with the line count
   * (issue-00090). The aggregate is asked rather than tracked, and the delete is kept for when the
   * flag is set, so a future partial-release use case gets replace semantics with no change here.
   */
  @Override
  protected void saveChildren(Reservation reservation) {
    if (!reservation.heldSetChanged()) {
      return;
    }
    String id = reservation.id().value();
    lines.delete(
        new LambdaQueryWrapper<ReservationLineDo>().eq(ReservationLineDo::getReservationId, id));
    List<ReservationLineDo> rows = new ArrayList<>();
    for (Map.Entry<Sku, Integer> held : reservation.held()) {
      ReservationLineDo row = new ReservationLineDo();
      row.setReservationId(id);
      row.setSku(held.getKey().value());
      row.setQuantity(held.getValue());
      rows.add(row);
    }
    // One statement rather than one per SKU; creation is the only path that writes these rows.
    lines.insert(rows);
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
