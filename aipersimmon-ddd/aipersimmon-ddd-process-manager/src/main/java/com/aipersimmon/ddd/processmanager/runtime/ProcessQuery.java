package com.aipersimmon.ddd.processmanager.runtime;

import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import java.util.Optional;

/**
 * The read-only port for inspecting a process instance. It offers no mutation: there is no back
 * door to change state or step. Richer query and operations surfaces (paging, timelines,
 * pending/dead effects, redrive) belong to the JDBC runtime module; this contract is the minimal
 * lookup by reference.
 */
public interface ProcessQuery {

  /** The current view of an instance, or empty if none exists for the reference. */
  Optional<ProcessView> find(ProcessRef processRef);
}
