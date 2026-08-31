import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { expect, vi } from 'vitest'
import { type FlowConfig, parseFlowConfig } from '../src/config.ts'
import type { DocEdge } from '../src/docRepository.ts'
import { DEBOUNCE_MS, type DocsWatcher } from '../src/watcher.ts'

/**
 * A stand-in for an agent CLI's headless form (spec-00005-FR-8): one node
 * process that prints a `claude-json` answer and exits, which is the whole shape
 * of a headless call. A first call is handed the payload alone, a resume the
 * thread's resume id and the follow-up after it, so what it answers says which
 * of the two forms ran.
 */
export const ASK_AGENT = `const [a, b] = process.argv.slice(1); process.stdout.write(JSON.stringify({ result: b === undefined ? 'answered: ' + a : 'resumed ' + a + ': ' + b, session_id: 'resume-1' }))`

const TEST_CONFIG = `
types:
  idea: { kind: living }
  prd: { kind: living }
  spec: { kind: living }
  design: { kind: living }
  rule: { kind: living }
  record: { kind: living }
  plan: { kind: work }
  issue: { kind: work }
  task: { kind: work }
relations: [parent, implements, informs, motivated_by, blocks, supersedes, verifies]
flow:
  idea:
    - { next: prd, carry: parent }
    - { next: spec, carry: parent }
  prd:
    - { next: spec, carry: parent }
  spec:
    - { next: rule, carry: informs }
    - { next: design, carry: informs }
    - { next: plan, carry: implements }
  plan:
    - { next: task, carry: parent }
    - { next: issue, carry: blocks }
    - { next: record, carry: parent }
entry: [idea, prd]
focus:
  idea: is it worth doing, and for whom
  prd: roles, scope, and what the value is traded against
  spec: the boundaries of each FR and the gaps in its acceptance
  rule: the branches the decision table leaves out, and the boundary values
  design: the structural trade-offs and the failure modes
agents:
  claude:
    command: node
    args: []
    cwd: docs
    headless:
      first:  [-e, ${JSON.stringify(ASK_AGENT)}, "{question}"]
      resume: [-e, ${JSON.stringify(ASK_AGENT)}, "{session}", "{question}"]
      capture: claude-json
`

/** `extra` is appended as further top-level YAML — a `carries` matrix, say. */
export function testConfig(extra = ''): FlowConfig {
  return parseFlowConfig(`${TEST_CONFIG}${extra}`, 'test-config')
}

/**
 * The test config with the types a cowrite session's own acceptance names
 * (spec-00006, rule-00001-BR-29): `reference`, which is the one type a session may
 * file a new document of (rule-00001-BR-30) and which the collapse filter judges
 * against the flow config like any other; and `integration` and `report`, two of
 * the types that had no agent path at all before the fifth kind (spec-00006-S3).
 * None of them is a flow entry type, and none is clarifiable, so nothing else in
 * the config moves with them.
 */
export function cowriteConfig(): FlowConfig {
  const config = testConfig()
  config.types.reference = 'living'
  config.types.integration = 'living'
  config.types.report = 'living'
  return config
}

/** Create a temporary docs tree; keys are paths relative to the docs dir. */
export function makeDocsDir(files: Record<string, string>): string {
  const docsDir = join(mkdtempSync(join(tmpdir(), 'wb-docs-')), 'docs')
  mkdirSync(docsDir, { recursive: true })
  for (const [relPath, content] of Object.entries(files)) {
    const target = join(docsDir, relPath)
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, content)
  }
  return docsDir
}

/**
 * Waits that depend on a real child process: spawning and exiting a node process
 * can take well over vi.waitFor's one-second default when the suite runs its
 * files in parallel, which made these waits flake.
 */
export const SESSION_WAIT = { timeout: 20_000, interval: 25 }

/**
 * Wait until a watch is not merely set up but delivering. The OS arms a watch
 * some way after the scan chokidar reports as ready, and a write into that
 * window is never seen at all; a probe file rewritten until it comes back as a
 * signal is the only honest way to know. It is written before any board
 * subscribes, and its removal is given the debounce window to fold away, so
 * nobody downstream hears the probe itself.
 */
export async function armWatch(watcher: DocsWatcher, docsDir: string): Promise<void> {
  await watcher.ready()
  let seen = 0
  const stop = watcher.subscribe(() => {
    seen += 1
  })
  const probe = join(docsDir, 'probe.tmp')
  await vi.waitFor(
    () => {
      writeFileSync(probe, String(seen))
      expect(seen).toBeGreaterThan(0)
    },
    // Slower than the debounce window: a probe rewritten faster than the window
    // closes keeps pushing the signal it waits for out of reach.
    { timeout: 10_000, interval: 3 * DEBOUNCE_MS },
  )
  stop()
  rmSync(probe)
  await new Promise((resolve) => setTimeout(resolve, 4 * DEBOUNCE_MS))
}

export function git(repoRoot: string, ...args: string[]): string {
  return execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8' })
}

/** A temporary git repo with a committed `docs/` tree. */
export function makeRepo(files: Record<string, string>): { repoRoot: string; docsDir: string } {
  const repoRoot = mkdtempSync(join(tmpdir(), 'wb-repo-'))
  git(repoRoot, 'init', '-q', '-b', 'main')
  git(repoRoot, 'config', 'user.email', 'whiteboard@example.test')
  git(repoRoot, 'config', 'user.name', 'Whiteboard Test')
  git(repoRoot, 'config', 'commit.gpgsign', 'false')
  const docsDir = join(repoRoot, 'docs')
  mkdirSync(docsDir, { recursive: true })
  for (const [relPath, content] of Object.entries(files)) {
    const target = join(docsDir, relPath)
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, content)
  }
  git(repoRoot, 'add', '.')
  git(repoRoot, 'commit', '-q', '--allow-empty', '-m', 'init')
  return { repoRoot, docsDir }
}

export function lastCommitMessage(repoRoot: string): string {
  return git(repoRoot, 'log', '-1', '--pretty=%s').trim()
}

export function lastCommitFiles(repoRoot: string): string[] {
  return git(repoRoot, 'show', '--name-only', '--pretty=', 'HEAD').trim().split('\n').filter(Boolean)
}

export function commitCount(repoRoot: string): number {
  return Number(git(repoRoot, 'rev-list', '--count', 'HEAD').trim())
}

/** A plain document-to-document edge: the id it declares is the document it lands on. */
export function relationEdge(from: string, to: string, relation: string, ok = true, declaredTargets = [to]): DocEdge {
  return { from, to, relation, ok, declaredTargets }
}

export function doc(frontMatter: Record<string, string>, body = ''): string {
  const lines = Object.entries(frontMatter).map(([key, value]) => `${key}: ${value}`)
  return `---\n${lines.join('\n')}\n---\n\n${body}`
}
