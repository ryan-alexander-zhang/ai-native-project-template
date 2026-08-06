package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.web.error.DefaultProblemFamilies;
import com.aipersimmon.ddd.web.error.ProblemCatalog;
import com.aipersimmon.ddd.web.error.ProblemDescriptor;
import com.aipersimmon.ddd.web.error.ProblemRegistry;
import com.aipersimmon.ddd.web.spi.IdempotencyPrincipalResolver;
import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.aipersimmon.ddd.web.spi.RequestSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * Auto-configures the web layer's zero-risk concerns — exception-to-ProblemDetail mapping, the
 * trace-id filter, cursor-aware Jackson, and i18n title resolution — each toggled by {@code
 * aipersimmon.ddd.web.*} and replaceable by a consumer bean. The application-exception advice is
 * added only when {@code -application} is on the classpath. Stateful opt-in concerns (idempotency,
 * replay, rate limiting) are contributed by later modules.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@EnableConfigurationProperties(AipersimmonDddWebProperties.class)
public class AipersimmonDddWebAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AipersimmonDddWebAutoConfiguration.class);

  /**
   * Where the idempotency filter sits: after Spring Security's chain (registered at {@code -100})
   * so a principal is established, and before application filters, which default to {@code
   * LOWEST_PRECEDENCE}. Not expressed as an offset from a Spring Security constant because that
   * dependency is optional and this class must load without it.
   */
  private static final int IDEMPOTENCY_FILTER_ORDER = 0;

  private static final boolean SECURITY_PRESENT =
      ClassUtils.isPresent(
          "org.springframework.security.core.context.SecurityContextHolder",
          AipersimmonDddWebAutoConfiguration.class.getClassLoader());

  @Bean
  @ConditionalOnMissingBean
  public ProblemTitleResolver problemTitleResolver(ObjectProvider<MessageSource> messageSource) {
    return new ProblemTitleResolver(messageSource.getIfAvailable());
  }

  /**
   * Builds the two-tier problem registry: every {@link ErrorCode} resolves to its per-code {@link
   * ProblemCatalog} override if one is registered, otherwise to its {@link
   * com.aipersimmon.ddd.core.error.ErrorCategory} {@link DefaultProblemFamilies family default}.
   * Resolution is total for any coded error — never {@code about:blank}.
   */
  @Bean
  @ConditionalOnMissingBean
  public ProblemRegistry aipersimmonDddProblemRegistry(ObjectProvider<ProblemCatalog> catalogs) {
    Map<String, ProblemDescriptor> overridesByCode = new HashMap<>();
    catalogs.forEach(
        catalog ->
            catalog
                .overrides()
                .forEach((code, descriptor) -> overridesByCode.put(code.code(), descriptor)));
    return code -> {
      ProblemDescriptor override = overridesByCode.get(code.code());
      return override != null ? override : DefaultProblemFamilies.DEFAULTS.get(code.category());
    };
  }

  @Bean
  @ConditionalOnMissingBean
  public ProblemDetailFactory aipersimmonDddProblemDetailFactory(
      ProblemRegistry registry, ProblemTitleResolver titleResolver) {
    return new ProblemDetailFactory(registry, titleResolver);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.problem-details",
      name = "enabled",
      matchIfMissing = true)
  public AipersimmonDddWebExceptionHandler aipersimmonDddWebExceptionHandler(
      ProblemDetailFactory factory) {
    return new AipersimmonDddWebExceptionHandler(factory);
  }

  /** Registered only when the Bean Validation API is present. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(ConstraintViolationException.class)
  static class ConstraintViolationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConstraintViolationAdvice constraintViolationAdvice(ProblemDetailFactory factory) {
      return new ConstraintViolationAdvice(factory);
    }
  }

  @Bean
  @ConditionalOnMissingBean(name = "aipersimmonDddRequestIdFilter")
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.request-id",
      name = "enabled",
      matchIfMissing = true)
  public FilterRegistrationBean<RequestIdFilter> aipersimmonDddRequestIdFilter(
      AipersimmonDddWebProperties properties) {
    AipersimmonDddWebProperties.RequestId requestId = properties.getRequestId();
    FilterRegistrationBean<RequestIdFilter> registration =
        new FilterRegistrationBean<>(
            new RequestIdFilter(requestId.getHeader(), requestId.isGenerateIfAbsent()));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  @Bean
  @ConditionalOnMissingBean
  public CursorJacksonModule aipersimmonDddCursorJacksonModule() {
    return new CursorJacksonModule();
  }

  @Bean
  @ConditionalOnMissingBean
  public ProblemHttpResponseWriter aipersimmonDddProblemHttpResponseWriter(
      ObjectProvider<ObjectMapper> objectMapper) {
    return new ProblemHttpResponseWriter(objectMapper.getIfAvailable(ObjectMapper::new));
  }

  // --- Idempotency (opt-in) -------------------------------------------------

  @Bean
  @ConditionalOnMissingBean(IdempotencyStore.class)
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.idempotency",
      name = "enabled",
      havingValue = "true")
  public IdempotencyStore aipersimmonDddIdempotencyStore(ObjectProvider<Clock> clock) {
    return new InMemoryIdempotencyStore(clock.getIfUnique(Clock::systemUTC));
  }

  /**
   * The caller an idempotency key belongs to. Reads the Spring Security context when it is on the
   * classpath; otherwise keys are scoped by tenant alone, which is what identity amounts to on an
   * endpoint with no authentication.
   */
  @Bean
  @ConditionalOnMissingBean(IdempotencyPrincipalResolver.class)
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.idempotency",
      name = "enabled",
      havingValue = "true")
  public IdempotencyPrincipalResolver aipersimmonDddIdempotencyPrincipalResolver() {
    if (SECURITY_PRESENT) {
      return new SecurityContextPrincipalResolver();
    }
    return Optional::empty;
  }

  @Bean
  @ConditionalOnMissingBean(name = "aipersimmonDddIdempotencyFilter")
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.idempotency",
      name = "enabled",
      havingValue = "true")
  public FilterRegistrationBean<IdempotencyFilter> aipersimmonDddIdempotencyFilter(
      IdempotencyStore store,
      IdempotencyPrincipalResolver principals,
      ProblemHttpResponseWriter writer,
      AipersimmonDddWebProperties properties) {
    AipersimmonDddWebProperties.Idempotency config = properties.getIdempotency();
    IdempotencyFilter filter =
        new IdempotencyFilter(
            store,
            principals,
            writer,
            config.getHeader(),
            config.getTtl(),
            config.getClaimLease(),
            config.isRequireKey(),
            config.getMethods().stream()
                .map(m -> m.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet()));
    FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
    // After the security filter chain, unlike the framework's other filters. A key is scoped to the
    // caller who owns it, so this filter has to see an established principal — and serving a stored
    // response before authentication would hand a client another caller's response body on nothing
    // more than a guessed key. Spring Security registers its chain at -100; anything above that
    // runs
    // once authentication has been applied.
    registration.setOrder(IDEMPOTENCY_FILTER_ORDER);
    return registration;
  }

  // --- Replay protection (opt-in; needs a signature verifier) ---------------

  @Bean
  @ConditionalOnMissingBean(ReplayGuard.class)
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.replay",
      name = "enabled",
      havingValue = "true")
  public ReplayGuard aipersimmonDddReplayGuard(ObjectProvider<Clock> clock) {
    return new InMemoryReplayGuard(clock.getIfUnique(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean(name = "aipersimmonDddReplayProtectionFilter")
  @ConditionalOnBean(RequestSignatureVerifier.class)
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.replay",
      name = "enabled",
      havingValue = "true")
  public FilterRegistrationBean<ReplayProtectionFilter> aipersimmonDddReplayProtectionFilter(
      RequestSignatureVerifier verifier,
      ReplayGuard replayGuard,
      ProblemHttpResponseWriter writer,
      ObjectProvider<Clock> clock,
      AipersimmonDddWebProperties properties) {
    AipersimmonDddWebProperties.Replay config = properties.getReplay();
    ReplayProtectionFilter filter =
        new ReplayProtectionFilter(
            verifier,
            replayGuard,
            writer,
            clock.getIfUnique(Clock::systemUTC),
            config.getTolerance(),
            config.getSignatureHeader(),
            config.getTimestampHeader(),
            config.getNonce().isEnabled(),
            config.getNonce().getHeader(),
            (int) Math.min(Integer.MAX_VALUE, config.getMaxBodySize().toBytes()));
    FilterRegistrationBean<ReplayProtectionFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
    // Left unset, a FilterRegistrationBean maps to /*. Naming patterns hands the matching to the
    // container, which matches on the path it dispatches on — so there is no second opinion about
    // what a path means for this filter to disagree with.
    if (!config.getUrlPatterns().isEmpty()) {
      registration.setUrlPatterns(config.getUrlPatterns());
    }
    return registration;
  }

  // --- Rate limiting (opt-in) -----------------------------------------------

  @Bean
  @ConditionalOnMissingBean(RateLimiter.class)
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.rate-limit",
      name = "enabled",
      havingValue = "true")
  public RateLimiter aipersimmonDddRateLimiter(ObjectProvider<Clock> clock) {
    return new InMemoryRateLimiter(clock.getIfUnique(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean(name = "aipersimmonDddRateLimitFilter")
  @ConditionalOnProperty(
      prefix = "aipersimmon.ddd.web.rate-limit",
      name = "enabled",
      havingValue = "true")
  public FilterRegistrationBean<RateLimitFilter> aipersimmonDddRateLimitFilter(
      RateLimiter rateLimiter,
      ProblemHttpResponseWriter writer,
      AipersimmonDddWebProperties properties) {
    RateLimitFilter filter = new RateLimitFilter(rateLimiter, writer, properties.getRateLimit());
    FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    return registration;
  }

  // --- In-memory fallback guard ---------------------------------------------

  /**
   * Reports every enabled concern that is running on its in-memory implementation, and can refuse
   * to start instead.
   *
   * <p>Each of these concerns exists to stop something: a repeated submission, a replayed signed
   * request, a caller exceeding its rate. An in-memory store keeps its state per JVM, so on a
   * second instance each of those stops working — the key, nonce or counter simply is not there.
   * The implementations say so in their Javadoc, but nobody reads a Javadoc while assembling a
   * deployment, and a concern was <em>deliberately switched on</em> by whoever is now unprotected.
   *
   * <p>Runs after all singletons are instantiated, so it can report the whole picture at once
   * rather than as three unrelated log lines, and so it sees the store that actually won — an
   * application bean or a {@code -web-store-*} module silences it, which is the point.
   *
   * <p>WARN by default because in-memory is the right default for development; set {@code
   * aipersimmon.ddd.web.allow-in-memory-stores=false} in production and this becomes a startup
   * failure.
   */
  @Bean
  public SmartInitializingSingleton aipersimmonDddInMemoryStoreGuard(
      AipersimmonDddWebProperties properties,
      ObjectProvider<IdempotencyStore> idempotencyStore,
      ObjectProvider<ReplayGuard> replayGuard,
      ObjectProvider<RateLimiter> rateLimiter) {
    return () -> {
      List<String> degraded = new ArrayList<>();
      if (properties.getIdempotency().isEnabled()
          && idempotencyStore.getIfAvailable() instanceof InMemoryIdempotencyStore) {
        degraded.add(
            "idempotency — a repeated request whose Idempotency-Key was first seen by another "
                + "instance is not recognised, so the side effect runs twice");
      }
      if (properties.getReplay().isEnabled()
          && replayGuard.getIfAvailable() instanceof InMemoryReplayGuard) {
        degraded.add(
            "replay protection — a nonce spent on one instance is unknown to the others, so the "
                + "same signed request can be replayed successfully");
      }
      if (properties.getRateLimit().isEnabled()
          && rateLimiter.getIfAvailable() instanceof InMemoryRateLimiter) {
        degraded.add(
            "rate limiting — each instance counts on its own, so the effective limit is the "
                + "configured one multiplied by the instance count");
      }
      if (degraded.isEmpty()) {
        return;
      }
      String detail =
          "aipersimmon-ddd-web: "
              + degraded.size()
              + " enabled concern(s) are running on an in-memory store, which holds its state per "
              + "JVM and therefore stops working as soon as there is a second instance:\n  - "
              + String.join("\n  - ", degraded)
              + "\nAdd a shared backend (aipersimmon-ddd-web-store-redis or "
              + "aipersimmon-ddd-web-store-mybatis-plus), or declare your own store bean. Single instance "
              + "and staying that way? Keep aipersimmon.ddd.web.allow-in-memory-stores=true.";
      if (!properties.isAllowInMemoryStores()) {
        throw new IllegalStateException(
            detail + " (allow-in-memory-stores=false, so this is a startup failure.)");
      }
      log.warn("{}", detail);
    };
  }

  /** Registered only when the application layer is present. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(ApplicationException.class)
  static class ApplicationExceptionConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApplicationExceptionAdvice applicationExceptionAdvice(ProblemDetailFactory factory) {
      return new ApplicationExceptionAdvice(factory);
    }
  }
}
