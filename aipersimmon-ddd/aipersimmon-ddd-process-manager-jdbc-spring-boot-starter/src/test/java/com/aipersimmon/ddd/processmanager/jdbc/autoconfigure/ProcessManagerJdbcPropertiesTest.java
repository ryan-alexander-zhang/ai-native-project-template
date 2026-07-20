package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Property validation must agree with how each value is consumed (issue-00021). */
class ProcessManagerJdbcPropertiesTest {

    private ProcessManagerJdbcProperties props() {
        return new ProcessManagerJdbcProperties();
    }

    @Test
    void rejectsAMisspelledSchemaValidationValue() {
        ProcessManagerJdbcProperties p = props();
        p.setSchemaValidation("Validate"); // wrong case: the gate is case-sensitive and would silently disable
        assertThrows(IllegalStateException.class, p::validate);
    }

    @Test
    void acceptsTheTwoValidSchemaValidationValues() {
        ProcessManagerJdbcProperties validate = props();
        validate.setSchemaValidation("validate");
        assertDoesNotThrow(validate::validate);
        ProcessManagerJdbcProperties none = props();
        none.setSchemaValidation("none");
        assertDoesNotThrow(none::validate);
    }

    @Test
    void acceptsDuplicateBusinessKeyValueCaseInsensitively() {
        ProcessManagerJdbcProperties p = props();
        p.setStartDuplicateBusinessKey("REJECT"); // consumed via valueOf(toUpperCase(...)), so must validate too
        assertDoesNotThrow(p::validate);
    }
}
