package com.example.samples.s10.points.domain;

/** What awarding points did. */
public enum AwardOutcome {
  AWARDED,
  /** The same reference was already awarded — a retried request, and therefore success. */
  ALREADY_AWARDED,
  /** The reference belongs to a reservation or a cancellation. A caller collision, not a retry. */
  REFERENCE_IN_USE
}
