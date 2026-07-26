package com.aipersimmon.ddd.processmanager.model;

/**
 * The version of a {@link com.aipersimmon.ddd.processmanager.definition.ProcessDefinition} for one
 * {@link ProcessType}. A running instance is pinned to the version it started on, so its decisions
 * stay stable while newer versions serve new instances. Several versions of a type may be
 * registered at once, but exactly one is active for new instances.
 *
 * @param value the version label (for example {@code "v1"}); non-blank
 */
public record DefinitionVersion(String value) {

  /**
   * The version a process has before it ever needs a second one, and the default of {@link
   * com.aipersimmon.ddd.processmanager.definition.ProcessDefinition#definitionVersion()}. Named
   * rather than repeated as a literal so the single-version case has one spelling.
   */
  public static final DefinitionVersion INITIAL = new DefinitionVersion("v1");

  public DefinitionVersion {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("definition version value required");
    }
  }
}
