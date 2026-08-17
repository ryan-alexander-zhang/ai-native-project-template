import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { type FlowConfig, parseFlowConfig } from '../src/config.ts'
import type { DocEdge } from '../src/docRepository.ts'

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
relations: [parent, implements, informs, supersedes, verifies]
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
agents:
  claude:
    command: node
    args: []
    cwd: docs
`

export function testConfig(): FlowConfig {
  return parseFlowConfig(TEST_CONFIG, 'test-config')
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
