package com.aipersimmon.ddd.processmanager.engine.store;

/** The lifecycle of a staged effect. */
public enum EffectStatus {
  PENDING,
  IN_FLIGHT,
  DELIVERED,
  DEAD,
  CANCELLED
}
