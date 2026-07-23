package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.operationlog.definition.OperationLogDefinition;
import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.ResolvableType;

/**
 * Binds each command input type to exactly one {@link OperationLogDefinition}. Built once at
 * startup from hand-written definitions plus annotation-synthesized ones; fails fast on a duplicate
 * definition, on a type targeted by both an annotation and a definition, or on an unresolvable
 * generic type.
 */
public final class OperationLogDefinitionRegistry {

  private final Map<Class<?>, OperationLogDefinition<?, ?>> byType;

  private OperationLogDefinitionRegistry(Map<Class<?>, OperationLogDefinition<?, ?>> byType) {
    this.byType = Map.copyOf(byType);
  }

  /**
   * @param codeDefinitions hand-written definition beans
   * @param annotated input type -&gt; annotation-synthesized definition
   */
  public static OperationLogDefinitionRegistry build(
      List<OperationLogDefinition<?, ?>> codeDefinitions,
      Map<Class<?>, OperationLogDefinition<?, ?>> annotated) {
    Map<Class<?>, OperationLogDefinition<?, ?>> byType = new HashMap<>();
    for (OperationLogDefinition<?, ?> definition : codeDefinitions) {
      Class<?> inputType = inputTypeOf(definition);
      if (byType.putIfAbsent(inputType, definition) != null) {
        throw new OperationLogException(
            "duplicate OperationLogDefinition for input type " + inputType.getName());
      }
    }
    for (Map.Entry<Class<?>, OperationLogDefinition<?, ?>> entry : annotated.entrySet()) {
      if (byType.containsKey(entry.getKey())) {
        throw new OperationLogException(
            "both @OperationLog and an OperationLogDefinition target "
                + entry.getKey().getName()
                + "; use exactly one");
      }
      byType.put(entry.getKey(), entry.getValue());
    }
    return new OperationLogDefinitionRegistry(byType);
  }

  /** The definition bound to {@code commandType}, if any. */
  @SuppressWarnings("unchecked")
  public Optional<OperationLogDefinition<Object, Object>> find(Class<?> commandType) {
    return Optional.ofNullable((OperationLogDefinition<Object, Object>) byType.get(commandType));
  }

  private static Class<?> inputTypeOf(OperationLogDefinition<?, ?> definition) {
    Class<?> type =
        ResolvableType.forInstance(definition)
            .as(OperationLogDefinition.class)
            .getGeneric(0)
            .resolve();
    if (type == null) {
      throw new OperationLogException(
          "cannot resolve the input type of "
              + definition.getClass().getName()
              + "; declare it with a concrete type parameter");
    }
    return type;
  }
}
