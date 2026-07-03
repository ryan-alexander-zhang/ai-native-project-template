# Prompts

This directory stores reusable prompt templates for coding agents.
Use `TEMPLATE.md` for front matter.

## Must Include

- the goal the prompt drives toward
- the role and context the agent should assume
- the inputs the prompt expects and the output it must produce
- how sub-agents are orchestrated, if the prompt fans out work

Add more when useful.

## Exclude

- one-off throwaway prompts
- project decisions or rules (keep those in their own docs)

## Note

A prompt doc is meant to be fed to an agent as-is and reused across runs.
Keep it self-contained so it works without extra explanation.
