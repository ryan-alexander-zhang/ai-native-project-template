# Unit Testing

Use this file to record the project-specific unit testing choice for this repo.

## Test Framework

Framework: `<fill in for this project>`. Note the short reason it is the chosen default.

This level has no template default. The repo template only pins
[INTEGRATION_TESTING.md](INTEGRATION_TESTING.md) to Testcontainers; pick the
unit framework that fits the project's language and toolchain.

## Command

List the command used to run unit tests locally and in CI.

## Scope

Define what unit tests must cover and what should stay out of unit tests.

## Gate

Define the minimum rule that must pass for unit testing at this repo.

## Report

Describe where to check unit test results, coverage, or CI output.

## AC id suffix

JUnit 5 method names carry the suffix verbatim: `void recordsTheUserWhoMadeIt__spec_00001_AC_5_1()`. Double underscores and digits are legal Java identifier characters, so no `@DisplayName` or tag is needed and `scripts/trace-check` greps the method name as written.
