package com.example.samples.s19.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Repository;

/** The port. */
@Repository
public interface Orders {

  void save(Order order);
}
