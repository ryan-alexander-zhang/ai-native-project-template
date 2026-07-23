package com.aipersimmon.ddd.operationlog.model;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * Optional reference to the template that produced an entry's summary, so history keeps meaning
 * after templates evolve.
 */
@ValueObject
public record TemplateRef(String key, String version) {

  /** A reference to a template key at a version. */
  public static TemplateRef of(String key, String version) {
    return new TemplateRef(key, version);
  }
}
