package com.aipersimmon.ddd.processmanager.engine.store;

/** The lifecycle of a scheduled deadline. */
public enum DeadlineStatus {
  PENDING,
  IN_FLIGHT,
  FIRED,
  DEAD,
  CANCELLED
}
