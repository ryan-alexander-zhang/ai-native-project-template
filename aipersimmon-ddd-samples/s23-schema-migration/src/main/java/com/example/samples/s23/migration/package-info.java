/**
 * How three sets of migrations share one database.
 *
 * <p>One class, and it is the answer to the question a modular monolith asks first: Spring Boot ships
 * exactly one Flyway, and this application needs three — ordering's, billing's, and the framework's.
 */
package com.example.samples.s23.migration;
