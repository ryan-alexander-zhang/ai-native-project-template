package com.example;

import com.example.ordering.domain.order.ManualReviewPolicy;
import com.example.ordering.domain.order.RestrictedSkuReviewPolicy;
import com.example.ordering.domain.shared.Sku;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the ordering context's replaceable business policies, in the composition root — the same
 * place the datasource, the transport and the Flyway component list are decided.
 *
 * <p>Why here and not in the application layer: which review rule applies is a <em>deployment</em>
 * decision, and {@code ordering-domain} is framework-free by construction (its only dependency is
 * {@code aipersimmon-ddd-core}), so it cannot read a property even if it wanted to. The domain
 * publishes the port and a default implementation that takes its configuration as a constructor
 * argument; this class is the only thing that knows those values came from YAML.
 *
 * <p><strong>Two levels of customisation, and they cost different amounts.</strong>
 *
 * <ul>
 *   <li><em>Change the watchlist</em> — set {@code ordering.review.restricted-skus} in {@code
 *       application.yml}. No code, no rebuild.
 *   <li><em>Change the rule</em> — declare your own {@link ManualReviewPolicy} bean (a fraud
 *       service, a product-classification lookup, a value threshold). {@link
 *       ConditionalOnMissingBean} makes the default below back off, so nothing here has to be
 *       deleted or edited.
 * </ul>
 *
 * Before this existed, both were the same operation: editing {@code PlaceOrderHandler}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderingPolicyConfig.ManualReviewProperties.class)
public class OrderingPolicyConfig {

  /**
   * @param restrictedSkus SKUs that force manual review. Empty is legitimate and means no order is
   *     ever held — the review branch simply becomes unreachable, which is a valid deployment.
   */
  @ConfigurationProperties("ordering.review")
  public record ManualReviewProperties(Set<String> restrictedSkus) {
    public ManualReviewProperties {
      restrictedSkus = restrictedSkus == null ? Set.of() : Set.copyOf(restrictedSkus);
    }
  }

  /**
   * The scaffold's default review rule. Backs off entirely if the application declares its own
   * {@link ManualReviewPolicy}.
   *
   * <p>The strings become {@link Sku} value objects here, at startup — so a blank or malformed
   * entry fails the context immediately rather than silently never matching a line at runtime. That
   * is the point of the domain taking {@code Set<Sku>} instead of {@code Set<String>}.
   */
  @Bean
  @ConditionalOnMissingBean(ManualReviewPolicy.class)
  ManualReviewPolicy manualReviewPolicy(ManualReviewProperties properties) {
    Set<Sku> watchlist = new LinkedHashSet<>();
    for (String sku : properties.restrictedSkus()) {
      watchlist.add(new Sku(sku));
    }
    return new RestrictedSkuReviewPolicy(watchlist);
  }
}
