package com.aipersimmon.ddd.processmanager.runtime;

import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.util.Optional;

/**
 * The read-only port for inspecting a process instance. It offers no mutation: there is no back
 * door to change state or step. Richer query and operations surfaces (paging, timelines,
 * pending/dead effects, redrive) belong to the JDBC runtime module; this contract is the minimal
 * lookup by reference or business key.
 */
public interface ProcessQuery {

  /** The current view of an instance, or empty if none exists for the reference. */
  Optional<ProcessView> find(ProcessRef processRef);

  /**
   * Resolve an instance's full {@link ProcessRef} from its business key, scoped to the ambient
   * tenant — or empty when no instance exists for that key. This is every consumer's first need (an
   * inbound result fact carries the business key, not the instance id), which is why it is part of
   * the port rather than a provider detail: a consumer holding only the port can decide whether a
   * flow exists before advancing it.
   */
  Optional<ProcessRef> findRef(ProcessType processType, ProcessBusinessKey businessKey);
}
