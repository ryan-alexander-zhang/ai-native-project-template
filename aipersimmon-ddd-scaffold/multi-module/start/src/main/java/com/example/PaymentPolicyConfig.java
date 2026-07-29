package com.example;

import com.example.payment.domain.AuthorizationPolicy;
import com.example.payment.domain.CeilingAuthorizationPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the payment context's authorization rule, in the composition root. Same reasoning as {@code
 * OrderingPolicyConfig}: {@code payment-domain} is framework-free, so it publishes a port plus a
 * default that takes its ceiling as a constructor argument, and this is the only class that knows
 * the value came from configuration.
 *
 * <p>Of the two levels, the second is the one that matters here. Raising the ceiling ({@code
 * payment.authorization.ceiling-minor}) keeps a stand-in a stand-in; a real deployment declares its
 * own {@link AuthorizationPolicy} bean that calls a provider, and {@link ConditionalOnMissingBean}
 * steps aside. When it does, two obligations from the port's javadoc carry over and are easy to
 * overlook: <em>do not throw</em> (a throw publishes nothing, and the order then dies of ordering's
 * PAYMENT deadline for a reason unrelated to the truth), and <em>carry the operation id as the
 * provider's idempotency key</em>.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentPolicyConfig.AuthorizationProperties.class)
public class PaymentPolicyConfig {

  /**
   * @param ceilingMinor amounts at or below this are authorised; above it, declined. Defaults to
   *     {@link CeilingAuthorizationPolicy#DEFAULT_CEILING_MINOR} so the demo works unconfigured,
   *     and so the tests can pin themselves to the constant rather than to a property a deployment
   *     is expected to change.
   */
  @ConfigurationProperties("payment.authorization")
  public record AuthorizationProperties(Long ceilingMinor) {
    public AuthorizationProperties {
      // Long, not long, and the difference is load-bearing: an absent property binds to null, which
      // is distinguishable from a configured 0. A primitive would bind to 0 and silently install a
      // ceiling that declines everything — the one value most easily mistaken for "unset".
      //
      // Long.valueOf rather than letting the ternary infer: with a primitive on one arm the ternary
      // unifies to long, so the other arm is unboxed and the result immediately reboxed (SpotBugs
      // BX_UNBOXING_IMMEDIATELY_REBOXED). Boxing explicitly keeps both arms Long.
      ceilingMinor =
          ceilingMinor == null
              ? Long.valueOf(CeilingAuthorizationPolicy.DEFAULT_CEILING_MINOR)
              : ceilingMinor;
    }
  }

  @Bean
  @ConditionalOnMissingBean(AuthorizationPolicy.class)
  AuthorizationPolicy authorizationPolicy(AuthorizationProperties properties) {
    return new CeilingAuthorizationPolicy(properties.ceilingMinor());
  }
}
