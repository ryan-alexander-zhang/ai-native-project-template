# Performance Testing

Project-specific load tooling. Runs the `load` method of
[QUALITY.md](QUALITY.md); what a run must show is the scenario's six parts.

## Tool

Tool: `<fill in for this project>`, with the short reason. No template default.
Pick by protocol and language: a service-boundary load generator for HTTP and
messaging (k6, Gatling), an in-process micro-benchmark harness for `build`
(JMH). Record the choice in a `decision` (`AGENTS.md` §2).

## Command

The command that runs a scenario locally and in CI, and how the scenario id
(`quality-<n>-QS-<i>.<k>`) selects its load profile.

## Scenario Profiles

Per `load` scenario: the script or profile reproducing its Source, Stimulus,
and Environment lines, and where it lives.

| QS | Profile | Volume / concurrency | Duration | Data set |
| --- | --- | --- | --- | --- |
| `<quality-00001-QS-1.1>` | `<script>` | `<500 rps>` | `<30 min>` | `<production-sized snapshot, anonymised>` |

## Environment

Sizing relative to production, data set, stubbed dependencies and their
latency; what may not be substituted unless the scenario's Environment line
allows it.

## Report

Where the report lands (path or CI artifact), its contents (percentiles,
throughput, error rate, duration, environment), how the `record` links it.
