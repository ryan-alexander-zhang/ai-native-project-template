package com.aipersimmon.ddd.operationlog.cqrs.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationActorResolver;
import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationTenantResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.FailureAnalysis;

class MissingOperationLogResolverFailureAnalyzerTest {

  private final MissingOperationLogResolverFailureAnalyzer analyzer =
      new MissingOperationLogResolverFailureAnalyzer();

  @Test
  void explains_a_missing_actor_resolver_with_a_concrete_action() {
    FailureAnalysis analysis =
        analyzer.analyze(new NoSuchBeanDefinitionException(OperationActorResolver.class));

    assertTrue(analysis.getDescription().contains("OperationActorResolver"));
    assertTrue(analysis.getDescription().contains("storage backend"));
    assertTrue(analysis.getDescription().contains("fails closed"));
    assertTrue(analysis.getAction().contains("@Bean"));
    assertTrue(analysis.getAction().contains("OperationActorResolver operationActorResolver()"));
  }

  @Test
  void explains_a_missing_tenant_resolver_including_the_global_default() {
    FailureAnalysis analysis =
        analyzer.analyze(new NoSuchBeanDefinitionException(OperationTenantResolver.class));

    assertTrue(analysis.getDescription().contains("OperationTenantResolver"));
    assertTrue(analysis.getAction().contains("GLOBAL"));
  }

  @Test
  void unwraps_the_resolver_cause_from_a_wrapping_failure() {
    // AbstractFailureAnalyzer walks the cause chain — the real failure is an
    // UnsatisfiedDependencyException wrapping the NoSuchBeanDefinitionException.
    FailureAnalysis analysis =
        analyzer.analyze(
            new IllegalStateException(
                new NoSuchBeanDefinitionException(OperationActorResolver.class)));

    assertTrue(analysis.getDescription().contains("OperationActorResolver"));
  }

  @Test
  void ignores_unrelated_missing_beans() {
    assertNull(analyzer.analyze(new NoSuchBeanDefinitionException(String.class)));
  }
}
