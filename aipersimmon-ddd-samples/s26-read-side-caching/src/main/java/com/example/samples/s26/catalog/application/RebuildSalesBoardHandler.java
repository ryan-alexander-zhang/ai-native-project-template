package com.example.samples.s26.catalog.application;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import org.springframework.stereotype.Component;

/** One statement, in one transaction: the projection is either the old one or the new one. */
@Component
class RebuildSalesBoardHandler implements CommandHandler<RebuildSalesBoard, Integer> {

  private final SalesBoard board;

  RebuildSalesBoardHandler(SalesBoard board) {
    this.board = board;
  }

  @Override
  public Integer handle(RebuildSalesBoard command, CommandContext context) {
    return board.rebuild(command.window());
  }
}
