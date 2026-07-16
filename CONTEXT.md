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
