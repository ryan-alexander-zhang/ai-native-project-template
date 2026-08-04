package com.example.samples.s27.customer.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * One question against the framework's outbox table.
 *
 * <p>Reading another component's table from application code is a coupling worth flinching at, and the flinch is
 * correct everywhere except here — see {@code OutboxQueue}'s javadoc for why erasure is the exception. What keeps
 * the coupling small is that it is one read of two columns and no writes: the erasure needs to know whether the
 * queue is drained, not to manage it.
 *
 * <p>{@code sent = FALSE} is the outbox's own definition of "still live" — the column its unsent index is
 * built on. The table also has a {@code sent_at} timestamp, and keying off that instead would work today and
 * would be reading a different column from the one the relay claims rows by. A row that was given up on has left
 * this table for the dead-letter table (S22), so it does not count here — which is itself a decision worth being
 * explicit about: a dead-lettered announcement about this customer will never be delivered, so it no longer
 * blocks the erasure, but it does still contain the personal data. That is the one hole in this design, and it is
 * named in §5 of the companion document rather than papered over.
 */
@Mapper
interface OutboxQueueMapper {

  @Select("SELECT COUNT(*) FROM aipersimmon_outbox WHERE subject = #{subject} AND sent = FALSE")
  long countUnsentFor(@Param("subject") String subject);
}
