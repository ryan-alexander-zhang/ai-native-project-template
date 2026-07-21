/**
 * Marker annotations that tag a type with its tactical DDD role — aggregate root, entity, value
 * object, repository, domain service, or domain event — without requiring it to implement a
 * framework interface. They carry no behaviour; tooling and architecture tests read them to verify
 * structure and to make intent explicit. Retained at runtime so reflective tooling can see them.
 */
package com.aipersimmon.ddd.core.annotation;
