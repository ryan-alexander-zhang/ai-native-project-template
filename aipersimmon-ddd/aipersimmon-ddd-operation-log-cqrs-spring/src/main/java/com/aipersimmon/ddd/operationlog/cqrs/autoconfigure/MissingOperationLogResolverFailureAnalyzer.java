package com.aipersimmon.ddd.operationlog.cqrs.autoconfigure;

import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationActorResolver;
import com.aipersimmon.ddd.operationlog.cqrs.capture.OperationTenantResolver;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns the generic "no bean of type {@code OperationActorResolver}/{@code
 * OperationTenantResolver}" startup failure into an actionable message. The capture layer requires
 * both resolvers once a storage backend is present (it stamps the trusted actor and tenant onto
 * every recorded row, from a trusted scope — never the command payload), and deliberately fails
 * closed rather than record a missing or forged identity. Without this analyzer a first-time
 * consumer only sees a bare {@code NoSuchBeanDefinitionException} that says which type is missing
 * but not why or how to fix it; this replaces it with a description and a concrete action.
 *
 * <p>Registered via {@code META-INF/spring.factories}. It only claims the two resolver types and
 * returns {@code null} for any other missing bean, so it never masks unrelated failures.
 */
public class MissingOperationLogResolverFailureAnalyzer
    extends AbstractFailureAnalyzer<NoSuchBeanDefinitionException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, NoSuchBeanDefinitionException cause) {
    Class<?> missing = cause.getBeanType();
    if (OperationActorResolver.class.equals(missing)) {
      return analysisFor(
          "OperationActorResolver",
          "the actor (who performed the operation)",
          "    @Bean\n"
              + "    OperationActorResolver operationActorResolver() {\n"
              + "      // resolve from your SecurityContext / request scope; never from the command\n"
              + "      return () -> Actor.system(\"my-service\");\n"
              + "    }");
    }
    if (OperationTenantResolver.class.equals(missing)) {
      return analysisFor(
          "OperationTenantResolver",
          "the tenant the operation belongs to",
          "    @Bean\n"
              + "    OperationTenantResolver operationTenantResolver() {\n"
              + "      // resolve from a trusted scope; return \"GLOBAL\" when multi-tenancy is off\n"
              + "      return () -> \"GLOBAL\";\n"
              + "    }");
    }
    return null;
  }

  private static FailureAnalysis analysisFor(String type, String role, String example) {
    String description =
        "The Operation Log capture layer is active (a storage backend is on the classpath), but no "
            + type
            + " bean is defined. The capture interceptors need it to stamp "
            + role
            + " onto every recorded row, from a trusted scope rather than the command payload, so "
            + "the component fails closed instead of recording a missing or forged identity.";
    String action =
        "Define a "
            + type
            + " bean in your composition root, for example:\n\n"
            + example
            + "\n\nApplications that use only the direct Operation Log API (no @OperationLog "
            + "annotations or definitions on commands) do not need it; in that case remove the "
            + "operation-log capture layer from the classpath.";
    return new FailureAnalysis(description, action, null);
  }
}
