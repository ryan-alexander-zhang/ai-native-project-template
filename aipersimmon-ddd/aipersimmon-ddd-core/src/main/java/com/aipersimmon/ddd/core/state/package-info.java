/**
 * A minimal, dependency-free state-transition guard
 * ({@link com.aipersimmon.ddd.core.state.Transitions}) for an aggregate's or
 * entity's lifecycle. It centralises the table of legal state transitions so a
 * domain object can validate a change from inside its own intention-revealing
 * methods, without exposing a mechanical transition API and without pulling in a
 * state-machine engine.
 */
package com.aipersimmon.ddd.core.state;
