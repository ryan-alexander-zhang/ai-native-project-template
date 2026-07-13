package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.web.error.DefaultProblemFamilies;
import com.aipersimmon.ddd.web.error.ProblemCatalog;
import com.aipersimmon.ddd.web.error.ProblemDescriptor;
import com.aipersimmon.ddd.web.error.ProblemRegistry;
import com.aipersimmon.ddd.web.spi.IdempotencyStore;
import com.aipersimmon.ddd.web.spi.RateLimiter;
import com.aipersimmon.ddd.web.spi.ReplayGuard;
import com.aipersimmon.ddd.web.spi.RequestSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
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

import java.time.Clock;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Auto-configures the web layer's zero-risk concerns — exception-to-ProblemDetail
 * mapping, the trace-id filter, cursor-aware Jackson, and i18n title resolution —
 * each toggled by {@code aipersimmon.ddd.web.*} and replaceable by a consumer bean.
 * The application-exception advice is added only when {@code -application} is on the
 * classpath. Stateful opt-in concerns (idempotency, replay, rate limiting) are
 * contributed by later modules.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@EnableConfigurationProperties(AipersimmonDddWebProperties.class)
public class AipersimmonDddWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProblemTitleResolver problemTitleResolver(ObjectProvider<MessageSource> messageSource) {
        return new ProblemTitleResolver(messageSource.getIfAvailable());
    }

    /**
     * Builds the two-tier problem registry: every {@link ErrorCode} resolves to its
     * per-code {@link ProblemCatalog} override if one is registered, otherwise to its
     * {@link com.aipersimmon.ddd.core.error.ErrorCategory} {@link DefaultProblemFamilies
     * family default}. Resolution is total for any coded error — never {@code about:blank}.
     */
    @Bean
    @ConditionalOnMissingBean
    public ProblemRegistry aipersimmonDddProblemRegistry(ObjectProvider<ProblemCatalog> catalogs) {
        Map<String, ProblemDescriptor> overridesByCode = new HashMap<>();
        catalogs.forEach(catalog -> catalog.overrides()
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
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.web.problem-details", name = "enabled", matchIfMissing = true)
    public AipersimmonDddWebExceptionHandler aipersimmonDddWebExceptionHandler(ProblemDetailFactory factory) {
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
    @ConditionalOnMissingBean(name = "aipersimmonDddTraceIdFilter")
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.web.trace", name = "enabled", matchIfMissing = true)
    public FilterRegistrationBean<TraceIdFilter> aipersimmonDddTraceIdFilter(AipersimmonDddWebProperties properties) {
        AipersimmonDddWebProperties.Trace trace = properties.getTrace();
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(
                new TraceIdFilter(trace.getHeader(), trace.isGenerateIfAbsent()));
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
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.web.idempotency", name = "enabled", havingValue = "true")
    public IdempotencyStore aipersimmonDddIdempotencyStore(ObjectProvider<Clock> clock) {
        return new InMemoryIdempotencyStore(clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean(name = "aipersimmonDddIdempotencyFilter")
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.web.idempotency", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<IdempotencyFilter> aipersimmonDddIdempotencyFilter(
            IdempotencyStore store, ProblemHttpResponseWriter writer, AipersimmonDddWebProperties properties) {
        AipersimmonDddWebProperties.Idempotency config = properties.getIdempotency();
        IdempotencyFilter filter = new IdempotencyFilter(store, writer, config.getHeader(), config.getTtl(),
                config.isRequireKey(),
                config.getMethods().stream().map(m -> m.toUpperCase(Locale.ROOT)).collect(Collectors.toSet()));
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);
        return registration;
    }

    // --- Replay protection (opt-in; needs a signature verifier) ---------------

    @Bean
    @ConditionalOnMissingBean(ReplayGuard.class)
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.web.replay", name = "enabled", havingValue = "true")
    public ReplayGuard aipersimmonDddReplayGuard(ObjectProvider<Clock> clock) {
        return new InMemoryReplayGuard(clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean(name = "aipersimmonDddReplayProtectionFilter")
    @ConditionalOnBean(RequestSignatureVerifier.class)
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.web.replay", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<ReplayProtectionFilter> aipersimmonDddReplayProtectionFilter(
            RequestSignatureVerifier verifier, ReplayGuard replayGuard, ProblemHttpResponseWriter writer,
            ObjectProvider<Clock> clock, AipersimmonDddWebProperties properties) {
        AipersimmonDddWebProperties.Replay config = properties.getReplay();
        ReplayProtectionFilter filter = new ReplayProtectionFilter(verifier, replayGuard, writer,
                clock.getIfAvailable(Clock::systemUTC), config.getTolerance(), config.getSignatureHeader(),
                config.getTimestampHeader(), config.getNonce().isEnabled(), config.getNonce().getHeader());
        FilterRegistrationBean<ReplayProtectionFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return registration;
    }

    // --- Rate limiting (opt-in) -----------------------------------------------

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.web.rate-limit", name = "enabled", havingValue = "true")
    public RateLimiter aipersimmonDddRateLimiter(ObjectProvider<Clock> clock) {
        return new InMemoryRateLimiter(clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean(name = "aipersimmonDddRateLimitFilter")
    @ConditionalOnProperty(prefix = "aipersimmon.ddd.web.rate-limit", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<RateLimitFilter> aipersimmonDddRateLimitFilter(
            RateLimiter rateLimiter, ProblemHttpResponseWriter writer, AipersimmonDddWebProperties properties) {
        RateLimitFilter filter = new RateLimitFilter(rateLimiter, writer, properties.getRateLimit());
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
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
