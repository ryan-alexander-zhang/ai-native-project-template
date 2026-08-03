package com.example.samples.s21.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;

/** The write port. */
@Repository
public interface Orders {

  void save(Order order);
}
