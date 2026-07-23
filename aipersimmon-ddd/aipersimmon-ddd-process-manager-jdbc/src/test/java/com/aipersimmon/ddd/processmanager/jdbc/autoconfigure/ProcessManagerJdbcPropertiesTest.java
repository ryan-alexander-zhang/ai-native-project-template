package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.engine.autoconfigure.ProcessManagerProperties;
import org.junit.jupiter.api.Test;

/** Property validation must agree with how each value is consumed. */
class ProcessManagerJdbcPropertiesTest {

  private ProcessManagerProperties props() {
    return new ProcessManagerProperties();
  }

  @Test
  void rejectsAMisspelledSchemaValidationValue() {
    ProcessManagerProperties p = props();
    p.setSchemaValidation(
        "Validate"); // wrong case: the gate is case-sensitive and would silently disable
    assertThrows(IllegalStateException.class, p::validate);
  }

  @Test
  void acceptsTheTwoValidSchemaValidationValues() {
    ProcessManagerProperties validate = props();
    validate.setSchemaValidation("validate");
    assertDoesNotThrow(validate::validate);
    ProcessManagerProperties none = props();
    none.setSchemaValidation("none");
    assertDoesNotThrow(none::validate);
  }

  @Test
  void acceptsDuplicateBusinessKeyValueCaseInsensitively() {
    ProcessManagerProperties p = props();
    p.setStartDuplicateBusinessKey(
        "REJECT"); // consumed via valueOf(toUpperCase(...)), so must validate too
    assertDoesNotThrow(p::validate);
  }
}
