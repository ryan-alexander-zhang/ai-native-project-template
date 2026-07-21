# SigNoz observability pack

Checked-in SigNoz dashboards + alert rules for an application built on the aipersimmon-ddd building
blocks. These JSON files are the **source of truth** — the same objects SigNoz's UI *Import JSON*
consumes — so they version with the code and import identically across every environment.

## Placeholders: `__APP__`

Display identity is **templated**, not hard-coded. Every dashboard title, tag, alert `service`
label, and per-service query filter uses the placeholder **`__APP__`**. `import.sh` substitutes it
with **`SIGNOZ_APP_NAME`** (set it to your service name — `spring.application.name` /
`otel.service.name`) at import time. So the checked-in JSON carries no application name; a service
called `ordering` gets dashboards titled `ordering · …` filtered to `service.name = 'ordering'`.

What is **not** a placeholder (library-owned, fixed — do not rename, or queries stop matching):

- metric names `aipersimmon.process.manager.*` (the `ProcessManagerMeters` constants)
- span-name prefixes `command *` / `process.advance *` / `outbox.publish *` / `effect.dispatch *`

## Prerequisite: export Micrometer metrics over OTLP

The library emits its SLIs (process-manager, HikariCP, executor) via **Micrometer**. The OTEL Spring
Boot starter exports only its own instrumentation metrics (jvm.*, http.server.*) unless the
Micrometer→OTLP bridge is on. Enable it in the app (already set in the multi-module scaffold's
`application.yml`):

```yaml
otel:
  instrumentation:
    micrometer:
      enabled: true
```

Without it, the **Process Manager** dashboard + its 7 metric alerts, and the HikariCP/executor rows
of the **Runtime (JVM)** dashboard, get no data. Traces and the OTel-native JVM metrics flow regardless.

## Dashboards

```
dashboards/process-manager.json   # "__APP__ · Process Manager"        — metrics-only reliability SLIs
dashboards/domain-spine.json      # "__APP__ · Domain Spine (traces)"  — command / advance / seam RED
dashboards/runtime-jvm.json       # "__APP__ · Runtime (JVM)"          — generic JVM + pool/executor saturation
alerts/*.json                     # 8 rules, labelled severity + category + service=__APP__
import.sh                         # env-parameterized importer (substitutes __APP__; upserts dashboards by title)
```

**`__APP__ · Process Manager`** — reads `aipersimmon.process.manager.*` gauges/counter, filtered to the service:

| Section | Panels |
|---|---|
| Redrive backlog | Effects / Deadlines dead-lettered (`dead.effects`, `dead.deadlines`) |
| Backlog age | Oldest pending effect / deadline age (`oldest.pending.*.age`, seconds) |
| Instance health | Suspended (by `source`), Stuck (`suspended.instances`, `stuck.instances`) |
| Contention | Advance conflict retries rate (`advance.conflict.retries`) |

**`__APP__ · Domain Spine (traces)`** — RED over the self-instrumented spans (matched by name prefix,
filtered to the service); latency from `duration_nano`:

| Section | Spans | Panels |
|---|---|---|
| Command bus | `command <Type>` | rate & errors by type, p50/p95/p99 |
| Process advance | `process.advance <ProcessType>` | rate by type, p95/p99 |
| Async seam | `outbox.publish <id>`, `effect.dispatch <id>` | rate + errors per seam |

**`__APP__ · Runtime (JVM)`** — generic runtime health (not DDD-specific):

| Section | Source | Panels |
|---|---|---|
| Memory | OTel runtime-telemetry | `jvm.memory.used` by area, `jvm.memory.used_after_last_gc` by pool |
| Threads & CPU | OTel runtime-telemetry | live threads, CPU utilization, GC time-rate |
| DB pool (HikariCP) | Micrometer (bridge) | connections active/idle/pending; pending value |
| Task executor | Micrometer (bridge) | executor active / queued |

## Alerts

8 rules, named by business meaning, labelled `severity`, `category`, and `service: __APP__`; every
query is filtered to `service.name = __APP__`.

| Category | Rule | Fires when |
|---|---|---|
| dead-letter (critical) | effects / deadlines dead-lettered | `dead.effects` / `dead.deadlines` > 0 |
| backlog (warning) | effect / deadline backlog aging | `oldest.pending.*.age` > 300s |
| data-integrity (warning) | instances stuck / suspended | `stuck.instances` / `suspended.instances` > 0 |
| saturation (warning) | advance conflict retries spiking | `advance.conflict.retries` +20 in 5m |
| availability (critical) | telemetry absent | `jvm.memory.used` stops arriving for 5m (dead-man's switch) |

> **Thresholds are placeholders.** The `>0` sentinels are absolute-count (correct at any volume); the
> age / spike thresholds (300s, +20) must be calibrated against real traffic.

## Usage

```bash
SIGNOZ_URL=http://localhost:8080 \
SIGNOZ_EMAIL=you@example.com SIGNOZ_PASSWORD='...' \
SIGNOZ_ORG_ID=<org-uuid> \
SIGNOZ_APP_NAME=<your-service-name> \
SIGNOZ_ALERT_CHANNEL=<a-configured-notification-channel> \
./import.sh
```

- **`SIGNOZ_APP_NAME` is required** — it replaces `__APP__` everywhere.
- Dashboards upsert by title (re-running never duplicates). Alerts POST (no upsert) — re-importing
  creates duplicates, so delete the old rules first if re-running.
- **Alerts need a notification channel** (env-specific); the importer injects `SIGNOZ_ALERT_CHANNEL`
  into each rule and **skips** alerts when it is unset. Manual UI: *Import JSON* (replace `__APP__`
  yourself), then attach a channel.

## Caveats (version-sensitive — verify against your SigNoz)

- `version: "v5"` is the schema version these were authored against.
- **JVM metric duplication:** with the Micrometer bridge on, `jvm.memory.used` exists twice (OTel-native
  `Gauge` + Micrometer `Sum`). The JVM panels pin `type: Gauge` to select the OTel-native series and
  avoid double-counting. HikariCP/executor exist only via Micrometer (`Gauge`).
- **Trace panels** filter with `LIKE 'command %'` and mark failures with `hasError = true` — confirm
  both against your SigNoz version.
- **Timer meters** `claim.latency` / `dispatch.latency` are emitted but not dashboarded: over OTLP they
  become histograms (`.bucket/.count/.sum`), version-sensitive names. Effect dispatch latency is read
  from the `effect.dispatch` spans instead (exporter-independent).
- **Absent-data** (dead-man's switch) uses `alertOnAbsent`/`absentFor`; confirm it fires by stopping telemetry.

## Extending to Grafana (later)

SigNoz is the current backend. These files are the SigNoz-native form of one **signal contract** (the
metric names + span-name/attribute conventions above). A future Grafana pack would live alongside as
`grafana/` and re-express the same contract — the signals don't change, only the rendering.
