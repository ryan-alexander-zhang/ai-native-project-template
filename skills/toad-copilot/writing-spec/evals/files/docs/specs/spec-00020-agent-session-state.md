---
id: spec-00020-agent-session-state
type: spec
role: main
status: active
parent: prd-00001-project-bootstrap-copilot
---

# Agent Session State

## Summary
Define how the local workspace tracks session state needed to reconnect an agent to an existing
project without silently reinitializing managed resources.

## Goals
- Preserve enough state to reopen an existing project session.
- Detect stale or incompatible stored state before reuse.
- Make recovery behavior explicit and user-visible.

## Non-Goals
- Sync state across multiple machines.
- Store arbitrary editor history.

## Assumptions
- State is stored locally on the same machine as the project workspace.
- The workspace path remains the primary stable identity for reconnecting a session.

## Required Inputs
- The current workspace path.
- The stored session metadata for that workspace.
- The current managed-resource version for the project.

## Design
The session store keeps one record per workspace. Each record includes the workspace path, a stable
workspace identifier, the managed-resource version, and timestamps for last attach and last repair.
When the tool reopens a workspace, it compares stored metadata against the current project state
before restoring the session.

## Data Flow
1. Read the workspace path.
2. Load the stored session record for that path.
3. Compare stored metadata with the current project state.
4. Restore the session when the metadata still matches.
5. Route the user into recovery when the metadata is stale or incompatible.

## Error Handling
- Missing record: offer a fresh attach flow.
- Corrupt record: ignore the record and ask the user to repair or recreate it.
- Version mismatch: block automatic restore and show a recovery path.

## Verification
- Unit tests cover record parsing and version comparison.
- Integration tests cover attach, reopen, and stale-state recovery.

## Success Criteria
- A valid record restores the expected workspace session.
- A stale or incompatible record never restores silently.
- The user always gets an explicit recovery path when automatic restore is blocked.
