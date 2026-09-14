# Prompts

Reusable prompt templates for coding agents. Front matter: `TEMPLATE.md`.

## Must Include

- goal
- role and context the agent assumes
- inputs expected, output required
- sub-agent orchestration, if it fans out work

Add more when useful.

## Exclude

- one-off throwaway prompts
- project decisions or rules (their own docs)

## Note

Fed to an agent as-is, reused across runs; self-contained.
