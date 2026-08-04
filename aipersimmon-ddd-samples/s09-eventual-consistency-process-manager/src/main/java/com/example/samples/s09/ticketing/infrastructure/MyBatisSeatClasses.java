package com.example.samples.s09.ticketing.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s09.ticketing.domain.SeatClass;
import com.example.samples.s09.ticketing.domain.SeatClassId;
import com.example.samples.s09.ticketing.domain.SeatClasses;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The seat class and its holds, saved as one aggregate.
 *
 * <p>The children are rewritten wholesale, which is the simplest correct strategy and the one S4 uses.
 * Its cost is honest and worth naming: the write is proportional to the number of holds ever taken
 * against this class, so a real deployment would either bound the aggregate (holds per class per event) or
 * append instead of rewriting. S17 is where that choice belongs; here the point is the flow, not the
 * mapping.
 */
@Repository
class MyBatisSeatClasses extends MybatisPlusAggregateRepository<SeatClass, SeatClassRow>
    implements SeatClasses {

  private final SeatClassMapper mapper;
  private final SeatHoldMapper holdMapper;

  MyBatisSeatClasses(
      SeatClassMapper mapper, SeatHoldMapper holdMapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
    this.holdMapper = holdMapper;
  }

  @Override
  public void save(SeatClass seatClass) {
    saveAggregate(seatClass);
  }

  @Override
  public Optional<SeatClass> find(SeatClassId id) {
    SeatClassRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    List<SeatClass.Hold> holds =
        holdMapper
            .selectList(
                new LambdaQueryWrapper<SeatHoldRow>().eq(SeatHoldRow::getSeatClass, id.value()))
            .stream()
            .map(
                hold ->
                    new SeatClass.Hold(hold.getOrderId(), hold.getHeldAt(), hold.getReleasedAt()))
            .toList();
    return Optional.of(SeatClass.reconstitute(id, row.getAvailable(), holds, row.getVersion()));
  }

  @Override
  protected SeatClassRow toRow(SeatClass seatClass) {
    SeatClassRow row = new SeatClassRow();
    row.setSeatClass(seatClass.id().value());
    row.setAvailable(seatClass.available());
    return row;
  }

  @Override
  protected void saveChildren(SeatClass seatClass) {
    holdMapper.delete(
        new LambdaQueryWrapper<SeatHoldRow>()
            .eq(SeatHoldRow::getSeatClass, seatClass.id().value()));
    for (SeatClass.Hold hold : seatClass.holds()) {
      SeatHoldRow row = new SeatHoldRow();
      row.setOrderId(hold.orderId());
      row.setSeatClass(seatClass.id().value());
      row.setHeldAt(hold.heldAt());
      row.setReleasedAt(hold.releasedAt());
      holdMapper.insert(row);
    }
  }
}
