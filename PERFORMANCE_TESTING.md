# Performance Testing

Use this file to record the project-specific load tooling for this repo. It runs
the `load` method of [QUALITY.md](QUALITY.md); what a run must show is the
quality scenario's six parts, not anything written here.

## Tool

Tool: `<fill in for this project>`. Note the short reason it is the chosen default.

This level has no template default. Pick by the artifact's protocol and the
team's language — a service-boundary load generator for HTTP and messaging
(k6 and Gatling are examples), an in-process micro-benchmark harness for the
`build` stage (JMH is one) — and record the choice in a `decision`, as for any
new dependency (`AGENTS.md` §2).

## Command

List the command that runs a scenario locally and in CI, and how the scenario
id (`quality-<n>-QS-<i>.<k>`) selects its load profile.

## Scenario Profiles

For each `load` scenario the quality docs declare: the script or profile that
reproduces its Source, Stimulus, and Environment lines, and where the profile
lives.

| QS | Profile | Volume / concurrency | Duration | Data set |
| --- | --- | --- | --- | --- |
| `<quality-00001-QS-1.1>` | `<script>` | `<500 rps>` | `<30 min>` | `<production-sized snapshot, anonymised>` |

## Environment

Describe the environment the scenarios run in — sizing relative to production,
data set, stubbed dependencies and their latency — and what may not be
substituted without the scenario's Environment line allowing it.

## Report

Where the report artifact lands (path or CI artifact name), what it must contain
(percentiles, throughput, error rate, duration, environment), and how the
`record` links it.
