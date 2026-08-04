package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s26.catalog.domain.Sku;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Append the fact, then move the projection — one transaction, two writes, and no cache eviction.
 *
 * <p>The two writes are in the same transaction on purpose, and that is the projection's real cost. It is
 * not the extra statement; it is that the sale path now cannot commit without also touching a row that
 * every other sale of the same product touches. Under enough concurrency on one popular sku that row is a
 * contention point the append-only table never was. The alternatives — updating the projection after the
 * commit, or from an event — trade that contention for a projection that can be missing a sale, which is
 * what {@link SalesBoard#rebuild} exists to repair. S12 takes the event route and pays that price; this
 * sample takes the transactional route so that the projection is never behind, and names what that buys.
 *
 * <p>The id comes from the framework's {@link IdGenerator}, so the highest-volume table in this schema
 * gets a time-ordered key rather than a random one — the library's own reason for having the SPI, applied
 * to a row it does not own.
 */
@Component
class RecordSaleHandler implements CommandHandler<RecordSale, Void> {

  private final OrderLines orderLines;
  private final SalesBoard board;
  private final IdGenerator ids;

  RecordSaleHandler(OrderLines orderLines, SalesBoard board, IdGenerator ids) {
    this.orderLines = orderLines;
    this.board = board;
    this.ids = ids;
  }

  @Override
  public Void handle(RecordSale command, CommandContext context) {
    Sku sku = new Sku(command.sku());
    orderLines.append(ids.newId(), sku, command.quantity(), Instant.now());
    board.add(sku, command.quantity());
    return null;
  }
}
