# Resilience Testing

Project-specific fault-injection tooling. Runs the `chaos` method of
[QUALITY.md](QUALITY.md); the hypothesis is the scenario's Response and Measure.

## Tool

Tool: `<fill in for this project>`, with the short reason. No template default.
Pick by where the fault is injected: a network proxy beside Testcontainers
([INTEGRATION_TESTING.md](INTEGRATION_TESTING.md); Toxiproxy) for dependency
latency and partition at `release`; a cluster-level tool (Chaos Mesh, Litmus,
the platform's own) for process, node, zone faults in staging; a game day for
`runtime`. Record the choice in a `decision` (`AGENTS.md` §2).

## Command

The command that runs an experiment locally and in CI, and how the scenario id
(`quality-<n>-QS-<i>.<k>`) selects its fault.

## Experiments

Per `chaos` scenario: fault injected, blast radius, restore step.

| QS | Fault | Blast radius | Restore | Abort condition |
| --- | --- | --- | --- | --- |
| `<quality-00001-QS-2.1>` | `<kill primary DB instance>` | `<staging, payment API only>` | `<failback, reconcile writes>` | `<error rate > 5 % for 60 s>` |

## Environment

Where experiments may run, who is told before one runs in a shared
environment, what is never injected into production.

## Report

Where the report lands, its contents (hypothesis, fault, observed response,
blast radius, restore proof), how the `record` links it.
