# Where the rest of the documentation lives

This project is generated from the `multi-module` archetype and ships on its own, so the
AiPersimmon DDD library's own documents are **not** in this tree. Everything below lives in the
library repository. This file is the single place that knows that, so nothing else in this project
has to name a file it does not have.

| Document | What it answers |
|---|---|
| `CHOOSING-MODULES.md` | Which `aipersimmon-ddd-*` modules to declare, and what each one drags in. Start here when adding a capability (a second persistence backend, Redis edge stores, a different messaging transport). |
| `CONFIGURATION.md` | Every `aipersimmon.ddd.*` property, and the production checklist — the settings a deployment is expected to decide rather than inherit (outbox lease budget and cleanup, inbox retention, tenancy policy). `start/src/main/resources/application.yml` answers that checklist inline; this is where the questions come from. |
| `ARCHITECTURE.md` | The layering and dependency rules the `ArchitectureTest` in `start` enforces. |
| `README.md` (library) | What the building blocks are and how they fit together. |

Replace this table's links with your own copy or fork URL when you adopt the project:

```
https://github.com/<your-org>/<your-fork>/blob/main/aipersimmon-ddd/CHOOSING-MODULES.md
```

## This project's own documents

| File | What it answers |
|---|---|
| [README.md](README.md) | The worked example: the three bounded contexts, the fulfilment flow, which building block is demonstrated where, and what each capability cost to adopt. |
| `start/src/main/resources/application.yml` | Every runtime decision this deployment has made, with the reasoning inline. |
