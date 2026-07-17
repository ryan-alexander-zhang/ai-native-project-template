package com.aipersimmon.ddd.processmanager.definition;

import com.aipersimmon.ddd.processmanager.exception.UnknownProcessDefinitionException;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Indexes the registered {@link ProcessDefinition}s so the runtime can resolve the
 * active version for a new instance and the pinned version for a running one. It does
 * not scan or reflect over business classes; the definitions are supplied explicitly.
 *
 * <p>Construction fails fast when a
 * {@code (processType, definitionVersion)} pair is registered twice, or when a
 * process type does not have exactly one version with
 * {@link ProcessDefinition#activeForNewInstances()} true.
 */
public final class ProcessDefinitionRegistry {

    private final Map<ProcessType, Map<DefinitionVersion, ProcessDefinition<?>>> byType = new HashMap<>();
    private final Map<ProcessType, ProcessDefinition<?>> activeByType = new HashMap<>();

    public ProcessDefinitionRegistry(Iterable<? extends ProcessDefinition<?>> definitions) {
        Map<ProcessType, ProcessDefinition<?>> active = new LinkedHashMap<>();
        for (ProcessDefinition<?> definition : definitions) {
            ProcessType type = definition.processType();
            DefinitionVersion version = definition.definitionVersion();
            Map<DefinitionVersion, ProcessDefinition<?>> versions =
                    byType.computeIfAbsent(type, t -> new LinkedHashMap<>());
            ProcessDefinition<?> existing = versions.put(version, definition);
            if (existing != null) {
                throw new IllegalStateException(
                        "two process definitions registered for " + type.value() + " " + version.value()
                                + ": " + existing.getClass().getName() + " and " + definition.getClass().getName());
            }
            if (definition.activeForNewInstances()) {
                ProcessDefinition<?> priorActive = active.put(type, definition);
                if (priorActive != null) {
                    throw new IllegalStateException(
                            "more than one active definition for process type " + type.value()
                                    + ": " + priorActive.definitionVersion().value()
                                    + " and " + version.value());
                }
            }
        }
        for (ProcessType type : byType.keySet()) {
            ProcessDefinition<?> activeDefinition = active.get(type);
            if (activeDefinition == null) {
                throw new IllegalStateException(
                        "no active definition for process type " + type.value()
                                + "; exactly one version must have activeForNewInstances=true");
            }
            activeByType.put(type, activeDefinition);
        }
    }

    /**
     * The version a new instance of {@code processType} starts on.
     *
     * @throws UnknownProcessDefinitionException if the type is not registered
     */
    public ProcessDefinition<?> resolveActive(ProcessType processType) {
        ProcessDefinition<?> definition = activeByType.get(processType);
        if (definition == null) {
            throw new UnknownProcessDefinitionException(processType);
        }
        return definition;
    }

    /**
     * The specific version a running instance is pinned to.
     *
     * @throws UnknownProcessDefinitionException if that type/version is not registered
     */
    public ProcessDefinition<?> resolve(ProcessType processType, DefinitionVersion version) {
        Map<DefinitionVersion, ProcessDefinition<?>> versions = byType.get(processType);
        ProcessDefinition<?> definition = versions == null ? null : versions.get(version);
        if (definition == null) {
            throw new UnknownProcessDefinitionException(processType, version);
        }
        return definition;
    }
}
