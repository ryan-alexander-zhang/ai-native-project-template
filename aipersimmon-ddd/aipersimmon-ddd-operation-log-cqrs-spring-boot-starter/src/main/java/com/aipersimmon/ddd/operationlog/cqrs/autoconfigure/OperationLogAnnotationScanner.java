package com.aipersimmon.ddd.operationlog.cqrs.autoconfigure;

import com.aipersimmon.ddd.operationlog.annotation.OperationLog;
import com.aipersimmon.ddd.operationlog.cqrs.capture.AnnotationOperationLogDefinition;
import com.aipersimmon.ddd.operationlog.definition.OperationLogDefinition;
import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Scans the application base packages for {@code @OperationLog}-annotated command types and
 * compiles each into an {@link AnnotationOperationLogDefinition}. Commands are records/classes (not
 * Spring beans), so component scanning by annotation is the discovery mechanism.
 */
final class OperationLogAnnotationScanner {

  private OperationLogAnnotationScanner() {}

  static Map<Class<?>, OperationLogDefinition<?, ?>> scan(List<String> basePackages) {
    ClassPathScanningCandidateComponentProvider provider =
        new ClassPathScanningCandidateComponentProvider(false);
    provider.addIncludeFilter(new AnnotationTypeFilter(OperationLog.class));
    Map<Class<?>, OperationLogDefinition<?, ?>> definitions = new HashMap<>();
    for (String basePackage : basePackages) {
      provider
          .findCandidateComponents(basePackage)
          .forEach(candidate -> register(definitions, candidate.getBeanClassName()));
    }
    return definitions;
  }

  private static void register(
      Map<Class<?>, OperationLogDefinition<?, ?>> definitions, String className) {
    Class<?> type = load(className);
    OperationLog annotation = type.getAnnotation(OperationLog.class);
    definitions.put(type, AnnotationOperationLogDefinition.compile(annotation));
  }

  private static Class<?> load(String className) {
    try {
      return ClassUtils.forName(className, OperationLogAnnotationScanner.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new OperationLogException("cannot load @OperationLog command class " + className, e);
    }
  }
}
