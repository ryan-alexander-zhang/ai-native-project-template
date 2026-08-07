package com.aipersimmon.ddd.archunit;

/**
 * The package segments the rules use to recognise a layer, in one place.
 *
 * <p>The interface layer is the only one with two accepted spellings. {@code adapter} is what the
 * archetype generates and what the multi-module scaffold uses; {@code interfaces} is at least as
 * common in the wild and is the word the framework's own package annotation picks
 * ({@code @InterfaceLayer}). A rules jar that recognised only one of them would silently not apply
 * to half the projects that adopt it — which is not a stricter rule, it is an absent one.
 *
 * <p>Every rule that names the interface layer now reads these constants. That was not always true:
 * four rules used to spell {@code "..adapter.."} inline, so in a project laid out with {@code
 * interfaces} they matched nothing at all — {@code domainShouldNotDependOnOuterLayers}, {@code
 * applicationShouldNotDependOnInfrastructureOrInterface}, {@code adapterShouldNotDependOnDomain}
 * and {@code integrationEventListenersShouldResideInAdapter} were each a rule a reader would
 * reasonably assume was running. The method name {@code
 * applicationShouldNotDependOnInfrastructureOrInterface} said "Interface" while matching only one
 * of the two words it could mean.
 */
final class Layers {

  /** The interface layer: inbound adapters and delivery mechanisms, under either accepted name. */
  static final String[] INTERFACE_LAYER = {"..adapter..", "..interfaces.."};

  /** Everything the domain sits inside of, and may therefore not depend on. */
  static final String[] OUTSIDE_THE_DOMAIN = {
    "..application..", "..infrastructure..", "..adapter..", "..interfaces.."
  };

  /** The two outward layers the application layer must not reach into. */
  static final String[] INFRASTRUCTURE_AND_INTERFACE = {
    "..infrastructure..", "..adapter..", "..interfaces.."
  };

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
