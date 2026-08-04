package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write port for seat inventory. */
@Repository
public interface SeatClasses {

  void save(SeatClass seatClass);

  Optional<SeatClass> find(SeatClassId id);
}
