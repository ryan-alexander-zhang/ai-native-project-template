# ARCHITECTURE.md Template

Template for `ARCHITECTURE.md`: an arc42-shaped index — short summary plus links per section, detail in the linked docs. C4 depth: L1 in §3, L2 in §5, L3 in `docs/design/` (one `module` design per component), never L4.

## Usage

- Copy everything below the `---` into `ARCHITECTURE.md`; delete each `>` block as its section is filled.
- Summarize and link; never duplicate. Link only `active` docs; superseding a linked doc updates the link here.
- Empty section: keep the heading, write `None.` No placeholders left.
- Diagrams in Mermaid.

---

# Architecture Overview

<The system in one paragraph.>

## 1. Introduction & Goals

> What and for whom; the ranked quality goals, mirrored from the `prd`. Requirements live in `docs/prd/` / `docs/quality/` / `docs/spec/` — link, don't restate.

## 2. Constraints

> Imposed limits only — technical, organizational, regulatory. Chosen trade-offs belong in §9.

| Constraint | Source |
| --- | --- |

## 3. Context & Scope

> System boundary: C4 L1 context diagram plus neighbor table. Third-party detail → `docs/integration/`.

```mermaid
flowchart LR
  U[User] --> S[System]
  S --> X[External system]
```

| Neighbor | Direction | Purpose |
| --- | --- | --- |

## 4. Solution Strategy

> The load-bearing choices — stack, decomposition, key patterns — one line each, citing `docs/decision/`.

## 5. Building Block View

> Annotated directory tree, then C4 L2 container diagram. Component internals (L3) → `docs/design/`: one `module` design per component.

```
<root>/
├── <dir>/    # <one line>
└── <dir>/    # <one line>
```

```mermaid
flowchart LR
  A[Container] --> B[Container]
```

## 6. Runtime View

> Key scenarios by name; each row links one `interaction` design, the sequence lives there.

| Scenario | Design |
| --- | --- |

## 7. Deployment View

> One row per environment linking its `deployment` design; the topology lives there. CI/CD pipeline shape in one line here. Procedures and runbooks → `docs/operation/`.

| Environment | Design |
| --- | --- |

## 8. Crosscutting Concepts

> System-wide rules — link, don't restate: security `SECURITY.md`, style `CODE_STYLE.md`, quality gates `CODE_QUALITY.md`, testing `TESTING.md`; each technical mechanism → its `mechanism` design, business invariants → `docs/rule/`.

| Concern | Design |
| --- | --- |

## 9. Architecture Decisions

> Index of `active` `docs/decision/` docs, one line each; content stays in the decision.

| Decision | Outcome |
| --- | --- |

## 10. Quality Requirements

> Index of the `active` `docs/quality/` docs refining §1's goals: one row per doc, its tags, the scenarios a reader should know exist. Measures stay in the quality docs (`QUALITY.md`), functional acceptance in `spec` ACs.
> An `ARCHITECTURE.md` filled before `docs/quality/` existed keeps inline scenarios until each has moved into a quality doc.

| Quality doc | Tags | Scenarios |
| --- | --- | --- |

## 11. Risks & Technical Debt

> Known risks and debt, with mitigation. Tracked items link `docs/issue/`.

| Item | Impact | Mitigation |
| --- | --- | --- |

## 12. Glossary

See `CONTEXT.md`.
