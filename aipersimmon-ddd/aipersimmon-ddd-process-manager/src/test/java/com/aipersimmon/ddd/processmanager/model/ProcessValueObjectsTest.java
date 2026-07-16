package com.aipersimmon.ddd.processmanager.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The identity value objects validate their arguments and behave as plain values. */
class ProcessValueObjectsTest {

    @Test
    void blankStringValueObjectsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessType(" "));
        assertThrows(IllegalArgumentException.class, () -> new ProcessBusinessKey(""));
        assertThrows(IllegalArgumentException.class, () -> new ProcessInstanceId(null));
        assertThrows(IllegalArgumentException.class, () -> new ProcessStep(" "));
        assertThrows(IllegalArgumentException.class, () -> new ProcessOutcome(""));
        assertThrows(IllegalArgumentException.class, () -> new DecisionCode(" "));
        assertThrows(IllegalArgumentException.class, () -> new DeadlineName(""));
        assertThrows(IllegalArgumentException.class, () -> new DefinitionVersion(" "));
    }

    @Test
    void numericValueObjectsEnforceBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessRevision(-1));
        assertThrows(IllegalArgumentException.class, () -> new StateSchemaVersion(0));
    }

    @Test
    void revisionStartsAtZeroAndAdvances() {
        assertEquals(0, ProcessRevision.initial().value());
        assertEquals(1, ProcessRevision.initial().next().value());
        assertEquals(6, new ProcessRevision(5).next().value());
    }

    @Test
    void processRefRequiresAllParts() {
        var id = new ProcessInstanceId("i-1");
        var type = new ProcessType("ordering.fulfilment");
        var key = new ProcessBusinessKey("order-1");
        assertThrows(IllegalArgumentException.class, () -> new ProcessRef(null, type, key));
        assertThrows(IllegalArgumentException.class, () -> new ProcessRef(id, null, key));
        assertThrows(IllegalArgumentException.class, () -> new ProcessRef(id, type, null));
        var ref = new ProcessRef(id, type, key);
        assertEquals("i-1", ref.instanceId().value());
    }
}
