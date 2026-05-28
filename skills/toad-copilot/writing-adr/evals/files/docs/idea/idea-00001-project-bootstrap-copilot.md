---
id: idea-00001-project-bootstrap-copilot
type: idea
role: main
status: draft
parent: <id>
---

# Idea Brief

## One-line Summary

A headless project bootstrap and management core for AI coding workspaces, with CLI as the MVP interface and room to expand into desktop, web, or other frontends later.

## Problem

When developers start a new project or adapt an existing one for AI coding, they usually piece together Git setup, docs structure, AGENTS.md, DESIGN.md, reusable files, and project-level skills by hand. The workflow is repetitive, inconsistent, and hard to standardize, which leads to document drift, weak project memory, and unstable agent context.

## Target User

Independent developers, small engineering teams, and technical leads who rely heavily on AI coding tools and want a repeatable way to set up and maintain project structure, context files, and reusable resources.

## Current Alternatives

Teams either do everything manually with shell commands, copied folder templates, and scattered Markdown files, or they combine separate tools for specs, docs, and AI workflows. That helps in parts, but project setup, resource management, and local agent context are still fragmented.

## Proposed Solution

Build a headless core that can create and manage projects and project resources, such as folders, preset files like `AGENTS.md`, and project-level skills. Ship the first version as a CLI so the system is usable immediately, while keeping the core independent from any single interface so it can later power desktop, web, or other frontends.

## Why Better

This makes project setup a reusable system instead of a collection of ad hoc scripts and habits. A headless core keeps the logic portable, while the CLI-first MVP keeps scope tight and useful. The result is a more stable project skeleton for both humans and agents.

## Why Now

AI coding is already mainstream enough that project context setup is becoming a recurring pain point. At the same time, the ecosystem now has enough reusable commands, templates, and open-source building blocks to validate this product quickly without building everything from scratch.

## Risks

- The product can easily expand from a focused bootstrap tool into an overly broad Project OS before the core workflow is proven.

- A headless architecture adds design discipline up front, and it could slow the MVP down if the boundaries are over-engineered.

- The product depends on local environment behavior, so cross-platform compatibility, permissions, and command reliability will strongly affect user trust.

## Decision

- [x] Continue

- [ ] Pause

- [ ] Drop
