package com.example.samples.s01.ordering.adapter;

import com.aipersimmon.ddd.web.error.ProblemCatalog;
import com.aipersimmon.ddd.web.error.ProblemDescriptor;
import com.example.samples.s01.ordering.domain.OrderingErrorCode;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The ordering context's problem-type overrides — one bean per context, because catalogs are merged by
 * code string with no duplicate detection.
 *
 * <p>Only errors with a genuinely distinct client contract belong here. The other two codes in
 * {@code OrderingErrorCode} deliberately stay on their category's family type and are told apart by
 * their {@code code} member, so the public catalogue of problem types does not grow one entry per
 * domain error.
 *
 * <p>The class is not named after its bean: a scanned {@code @Configuration} class is itself a bean
 * named after the class, so a {@code @Bean} method of the same name collides with it at startup.
 */
@Configuration(proxyBeanMethods = false)
class OrderingProblemConfig {

  @Bean
  ProblemCatalog orderingProblemCatalog() {
    return () ->
        Map.of(
            OrderingErrorCode.ORDER_NOT_CONFIRMABLE,
            new ProblemDescriptor(
                "/problems/order-not-confirmable", 409, "ordering.order-not-confirmable.title"));
  }
}
