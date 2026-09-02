# Docs Whiteboard

A local, single-user board over this repo's `docs/` tree: the documents are nodes,
their front matter relations are edges, and every action writes back to the
Markdown files and commits. Implements
[spec-00001-docs-whiteboard](../../docs/spec/spec-00001-docs-whiteboard.md) per
[design-00001-docs-whiteboard](../../docs/design/design-00001-docs-whiteboard.md).

## Commands

Run from this directory (`tools/whiteboard`):

| Task | Command |
| --- | --- |
| Setup | `npm install` |
| Build the UI | `npm run build` |
| Run | `npm start` (honours `PORT`, default 4173) |
| Test | `npm test` |
| Coverage | `npm run test:coverage` |
| Typecheck | `npm run typecheck` |
| UI dev server | `npm run dev` (proxies `/api` to a board started with `npm start`) |

The board finds its repo by walking up from wherever you launch it for the nearest
`whiteboard.config.yaml`, so `npm start` works from any directory in the repo, and
it reads that directory's `docs/`.

`npm start` alone is enough to use the board: it serves the built UI from
`dist/web`, so run `npm run build` once first. `npm run dev` is for working on the
UI itself — it starts Vite with hot reload and proxies `/api` to a board that must
already be running via `npm start` in another terminal.

## Configuration

`whiteboard.config.yaml` at the repo root is the machine-readable carrier of
[rule-00001-docs-workflow](../../docs/rule/rule-00001-docs-workflow.md): the type
split, the relation fields, the product flow, and the agent commands. It is
validated at startup, and a missing or invalid config stops the board — there is
no built-in default.

### Adding an agent CLI

The `agents` block names the CLI a session runs. A session's working directory is
`docs/`, which is the write-scope constraint the MVP relies on. **Before adding a
CLI here, verify it against `spec-00001-AC-13.2`**: a write outside the docs tree
must not land. An unverified CLI does not belong in the shipped config.

That block is the **project layer**, shared through git. Over it sits a **local
layer** — `.whiteboard/agents.json`, which git ignores — holding this machine's
own choices: a different `model` or `command` for an entry the project declares,
extra `env`, an entry only this machine has, a disabled entry, a different
default. Edit it from the settings panel in the board's top bar, or by hand; it
is re-read on every session start, so a change takes effect without a restart,
and an ill-formed file is ignored whole rather than stopping the board. The
verification discipline above applies to the **project layer only**: what runs on
your own machine is your own call.

## Notes

- `node-pty` ships prebuilt binaries whose `spawn-helper` needs the executable
  bit. npm blocks dependency install scripts, so `postinstall` restores it here;
  without it every session fails with `posix_spawnp failed`.
- The source runs on Node's native TypeScript stripping, so intra-package imports
  carry the real `.ts` extension and the code avoids constructor parameter
  properties, which strip-only mode rejects.
