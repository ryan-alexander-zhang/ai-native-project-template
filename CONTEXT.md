# Aipersimmon DDD Toolkit

This context defines the canonical language used by the toolkit for durable, cross-aggregate process coordination.

## Language

**Process Manager**:
A durable coordinator that reacts to facts, records process progress, and requests the next external actions without owning aggregate invariants.
_Avoid_: Saga when naming the toolkit's generic coordinator

**Process Definition**:
A deterministic description of how one Process Manager type decides its next state and effects from the current state and an input.
_Avoid_: Workflow engine, aggregate state machine

**Process Lifecycle**:
The runtime condition of a Process Manager instance, distinct from both its Business Step and every aggregate status.
_Avoid_: Order status, workflow step

**Business Step**:
Consumer-owned vocabulary describing where a Process Manager is within a particular business process.
_Avoid_: Process Lifecycle, aggregate status

**Effect**:
A durable request produced by a Process Definition for later external dispatch or deadline management.
_Avoid_: Side effect already completed

**Deadline**:
A durable, named future input associated with one Process Manager instance.
_Avoid_: In-memory timer, callback

**Process Provider**:
One execution implementation behind a bounded-context-owned process port.
_Avoid_: Universal workflow-engine adapter

## Flagged ambiguities

**State**:
Use Process Lifecycle for runtime condition, Business Step for process progress, and aggregate status for domain entity lifecycle; do not use state alone when more than one is in scope.

## Example dialogue

> Developer: Does the Process Lifecycle become `AWAITING_PAYMENT`?
>
> Domain expert: No. `AWAITING_PAYMENT` is the Business Step. The Process Lifecycle remains running while the Process Definition emits and awaits Effects.
>
> Developer: And an aggregate still decides whether its own status transition is valid?
>
> Domain expert: Yes. The Process Manager coordinates requests; it does not replace aggregate invariants.

## Operation Log Language

Canonical terms for the Operation Log component. Definitions and rationale live in
`docs/decision/decision-00017-operation-log-component-boundaries.md` and `docs/design/design-00008-operation-log-component.md`.

**Operation Log**:
A business-readable record of who did what to which business object, with what outcome and which key fields changed.
_Avoid_: Audit Log, Technical Log, Domain Event, generic application/SLF4J log

**Audit Log**:
A separate compliance/security-grade evidence record (strong identity, tamper-evident, enforced retention); not delivered by this component.
_Avoid_: labeling an append-only Operation Log as an audit log

**Operation Outcome**:
The business result of an operation — `SUCCEEDED`, `REJECTED`, or `FAILED`.
_Avoid_: HTTP status, exception type, aggregate status

**Transaction Completion**:
Whether the business transaction took effect — `COMMITTED`, `ROLLED_BACK`, `NOT_STARTED`, `UNKNOWN`; orthogonal to Operation Outcome.
_Avoid_: conflating with Operation Outcome; a single success boolean

**Actor**:
The trusted snapshot of who performed an operation (type/id/displayName), captured at record time from a trusted boundary.
_Avoid_: reading the actor from command payload; a mutable current-user lookup

**Target**:
The single primary business object an operation acts on (type/id/displayName).
_Avoid_: multiple targets per record (not in v1); leaking sensitive natural ids

**OperationChange**:
An explicit, allowlisted before/after field change (field/label/before/after).
_Avoid_: reflection-based full-object diff; unbounded change sets

## Flagged ambiguities (Operation Log)

**Outcome vs Completion**:
Use Operation Outcome for the business result and Transaction Completion for whether it persisted; the two are orthogonal — a normal return may be `REJECTED + COMMITTED`, an exception may be `REJECTED + NOT_STARTED` or `FAILED + ROLLED_BACK`. Never collapse them into one success flag.

**Operation Log vs Domain Event**:
A Domain Event states a fact that happened in the domain and may drive behavior; an Operation Log is a user-readable, actor-attributed record that must never drive domain behavior. One may help produce the other, but neither replaces it.
