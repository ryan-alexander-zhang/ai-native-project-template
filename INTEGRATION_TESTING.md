# Integration Testing

Use this file to record the project-specific integration testing choice for this repo.

## Test Framework

Framework: Testcontainers (template default).

Testcontainers is the pinned default for integration testing in this template
because it provides real boundaries (database, messaging, filesystem) without
shared environment drift. Replace it only if the project cannot use
Testcontainers (e.g., no Docker runtime available); record the reason in a
decision doc.

## Command

List the command used to run integration tests locally and in CI.

## Scope

Define what integration tests must cover and what should stay out of integration tests.

## Environment

Describe the services, containers, and runtime setup required for integration tests. Use Testcontainers as the default way to provide dependencies.

## Gate

Define the minimum rule that must pass for integration testing at this repo.

## Report

Describe where to check integration test results, logs, or CI output.
