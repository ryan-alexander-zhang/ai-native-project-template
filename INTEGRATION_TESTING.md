# Integration Testing

Project-specific integration testing choice.

## Test Framework

Framework: Testcontainers (template default): real boundaries (database,
messaging, filesystem) without shared-environment drift. Replace only when the
project cannot use it (no Docker runtime); reason in a `decision`.

## Command

The command that runs integration tests locally and in CI.

## Scope

What integration tests cover, and what stays out.

## Environment

Services, containers, runtime setup; dependencies via Testcontainers by default.

## Gate

The minimum rule that must pass.

## Report

Where results, logs, or CI output are checked.
