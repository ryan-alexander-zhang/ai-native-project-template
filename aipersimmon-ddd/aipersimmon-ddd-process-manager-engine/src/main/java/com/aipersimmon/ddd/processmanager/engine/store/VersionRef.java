package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;

/** A distinct schema-version pair a live instance depends on, for startup fail-fast. */
public record VersionRef(
    ProcessType processType,
    DefinitionVersion definitionVersion,
    StateSchemaVersion stateSchemaVersion) {}
