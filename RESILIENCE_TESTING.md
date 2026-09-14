# Resilience Testing

Use this file to record the project-specific fault-injection tooling for this
repo. It runs the `chaos` method of [QUALITY.md](QUALITY.md); the hypothesis a
run tests is the quality scenario's Response and Measure, not anything written
here.

## Tool

Tool: `<fill in for this project>`. Note the short reason it is the chosen default.

This level has no template default. Pick by where the fault is injected: a
network proxy alongside Testcontainers (per [INTEGRATION_TESTING.md](INTEGRATION_TESTING.md);
Toxiproxy is one) for dependency latency and partition at the `release` stage
in a test environment; a cluster-level tool (Chaos Mesh, Litmus, or the
platform's own are examples) for process, node, and zone faults in staging; a
game day for `runtime`. Record the choice in a `decision`, as for any new
dependency (`AGENTS.md` §2).

## Command

List the command that runs an experiment locally and in CI, and how the
scenario id (`quality-<n>-QS-<i>.<k>`) selects its fault.

## Experiments

For each `chaos` scenario the quality docs declare: the fault injected, the
blast radius, and the restore step.

| QS | Fault | Blast radius | Restore | Abort condition |
| --- | --- | --- | --- | --- |
| `<quality-00001-QS-2.1>` | `<kill primary DB instance>` | `<staging, payment API only>` | `<failback, reconcile writes>` | `<error rate > 5 % for 60 s>` |

## Environment

Describe where experiments may run, who must be told before one runs in a
shared environment, and what may never be injected into production.

## Report

Where the experiment report lands, what it must contain (hypothesis, fault,
observed response, blast radius, restore proof), and how the `record` links it.
