package com.aipersimmon.ddd.processmanager.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.exception.UnknownProcessDefinitionException;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Registry indexing and the one-active-version-per-type startup rule. */
class ProcessDefinitionRegistryTest {

    private static final ProcessType ORDERING = new ProcessType("ordering.fulfilment");

    /** Minimal definition: only its identity matters to the registry. */
    static final class TestDefinition implements ProcessDefinition<Object> {
        private final ProcessType type;
        private final DefinitionVersion version;
        private final boolean active;

        TestDefinition(ProcessType type, String version, boolean active) {
            this.type = type;
            this.version = new DefinitionVersion(version);
            this.active = active;
        }

        @Override
        public ProcessType processType() {
            return type;
        }

        @Override
        public DefinitionVersion definitionVersion() {
            return version;
        }

        @Override
        public boolean activeForNewInstances() {
            return active;
        }

        @Override
        public com.aipersimmon.ddd.processmanager.model.StateSchemaVersion stateSchemaVersion() {
            return new com.aipersimmon.ddd.processmanager.model.StateSchemaVersion(1);
        }

        @Override
        public ProcessDecision<Object> start(ProcessInput input, ProcessContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProcessDecision<Object> react(Object state, ProcessInput input, ProcessContext context) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void resolvesActiveAndPinnedVersions() {
        TestDefinition v1 = new TestDefinition(ORDERING, "v1", false);
        TestDefinition v2 = new TestDefinition(ORDERING, "v2", true);
        var registry = new ProcessDefinitionRegistry(List.of(v1, v2));

        assertSame(v2, registry.resolveActive(ORDERING));
        assertSame(v1, registry.resolve(ORDERING, new DefinitionVersion("v1")));
        assertSame(v2, registry.resolve(ORDERING, new DefinitionVersion("v2")));
    }

    @Test
    void duplicateTypeVersionFailsFast() {
        TestDefinition a = new TestDefinition(ORDERING, "v1", true);
        TestDefinition b = new TestDefinition(ORDERING, "v1", false);
        assertThrows(IllegalStateException.class, () -> new ProcessDefinitionRegistry(List.of(a, b)));
    }

    @Test
    void moreThanOneActiveVersionFailsFast() {
        TestDefinition a = new TestDefinition(ORDERING, "v1", true);
        TestDefinition b = new TestDefinition(ORDERING, "v2", true);
        assertThrows(IllegalStateException.class, () -> new ProcessDefinitionRegistry(List.of(a, b)));
    }

    @Test
    void noActiveVersionFailsFast() {
        TestDefinition a = new TestDefinition(ORDERING, "v1", false);
        assertThrows(IllegalStateException.class, () -> new ProcessDefinitionRegistry(List.of(a)));
    }

    @Test
    void unknownTypeOrVersionThrows() {
        var registry = new ProcessDefinitionRegistry(
                List.of(new TestDefinition(ORDERING, "v1", true)));

        assertThrows(UnknownProcessDefinitionException.class,
                () -> registry.resolveActive(new ProcessType("unknown")));
        assertThrows(UnknownProcessDefinitionException.class,
                () -> registry.resolve(ORDERING, new DefinitionVersion("v9")));
    }

    @Test
    void emptyRegistryHasNoDefinitions() {
        var registry = new ProcessDefinitionRegistry(List.of());
        assertThrows(UnknownProcessDefinitionException.class, () -> registry.resolveActive(ORDERING));
    }

    @Test
    void reportsRequestedVersionOnUnknown() {
        var registry = new ProcessDefinitionRegistry(
                List.of(new TestDefinition(ORDERING, "v1", true)));
        var ex = assertThrows(UnknownProcessDefinitionException.class,
                () -> registry.resolve(ORDERING, new DefinitionVersion("v9")));
        assertEquals("v9", ex.definitionVersion().value());
    }
}
