/**
 * Bootstrap package of the modular monolith. Both bounded contexts live as
 * sub-packages of this one deployable — {@code com.example.ordering} and
 * {@code com.example.inventory} — each with its own api / domain / application /
 * infrastructure / adapter layers. Unlike the multi-module topology, the bounded
 * context and layer boundaries here are not separate Maven modules; they are
 * packages, enforced at test time by the architecture tests rather than at compile
 * time. Cross-context references go only through the other context's {@code api}
 * package.
 */
package com.example;
