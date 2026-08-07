package com.aipersimmon.ddd.archunit;

/**
 * The package segments the rules use to recognise a layer, in one place.
 *
 * <p>The interface layer is the only one with two accepted spellings. {@code adapter} is what the
 * archetype generates and what the multi-module scaffold uses; {@code interfaces} is at least as
 * common in the wild, is what most of this project's own samples use for their web tier, and is the
 * word the framework's own package annotation picks ({@code @InterfaceLayer}). A rules jar that
 * recognised only one of them would silently not apply to half the projects that adopt it — which
 * is not a stricter rule, it is an absent one.
 *
 * <p><strong>Only the newest rules read this constant.</strong> The older ones still spell {@code
 * "..adapter.."} inline, so in a project that says {@code interfaces} they match nothing: {@code
 * LayeringRules.domainShouldNotDependOnOuterLayers}, {@code
 * applicationShouldNotDependOnInfrastructureOrInterface}, {@code adapterShouldNotDependOnDomain}
 * and {@code EventRules.integrationEventListenersShouldResideInAdapter} are all in that state.
 * Pointing them at this constant is a separate, deliberate change — it widens what four existing
 * rules report, so it belongs in its own commit rather than riding along with new rules.
 */
final class Layers {

  /** The interface layer: inbound adapters and delivery mechanisms, under either accepted name. */
  static final String[] INTERFACE_LAYER = {"..adapter..", "..interfaces.."};

  /**
   * Every layer except infrastructure — the ones that must not see a persistence detail.
   *
   * <p>Spelled as a list of layers rather than as "outside infrastructure" on purpose: the
   * composition root has no layer segment at all, and it is precisely the place that legitimately
   * names concrete types from every module in order to wire them together.
   */
  static final String[] INNER_AND_INTERFACE_LAYERS = {
    "..domain..", "..application..", "..adapter..", "..interfaces.."
  };

  private Layers() {}
}
