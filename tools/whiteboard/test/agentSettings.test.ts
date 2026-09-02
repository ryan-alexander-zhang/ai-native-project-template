import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { EffectiveAgents, LocalSettingsError, mergeAgents, readLocalSettings } from '../src/agentSettings.ts'
import type { AgentConfig } from '../src/config.ts'
import type { HeadlessConfig } from '../src/headless.ts'

/** A headless declaration the entry checks pass, so a test can put one on an entry and take it off again. */
const HEADLESS: HeadlessConfig = {
  first: ['-p', '{question}'],
  resume: ['-p', '--resume', '{session}', '{question}'],
  capture: 'claude-json',
}

const PROJECT: AgentConfig[] = [
  { name: 'claude', command: 'first-cli', args: ['--model={model}'], cwd: 'docs', model: 'm1' },
  { name: 'other', command: 'second-cli', args: ['--yolo'], cwd: 'docs' },
]

/** A repo root with the local layer written into it, or with none at all. */
function repoWith(local?: unknown): string {
  const repoRoot = mkdtempSync(join(tmpdir(), 'wb-settings-'))
  if (local !== undefined) {
    mkdirSync(join(repoRoot, '.whiteboard'), { recursive: true })
    writeFileSync(
      join(repoRoot, '.whiteboard', 'agents.json'),
      typeof local === 'string' ? local : JSON.stringify(local),
    )
  }
  return repoRoot
}

/**
 * The shape check of the four keys (design-00001 §13.1): what the file **is**,
 * before anything is merged. Every refusal names the key, because that is what
 * the panel points at (spec-00009-FR-6).
 */
describe('readLocalSettings', () => {
  it('reads the four keys, each of them optional', () => {
    expect(readLocalSettings({})).toEqual({
      default: undefined,
      disabled: undefined,
      overrides: undefined,
      entries: undefined,
    })
  })

  it('refuses a file that is not the four-key shape, naming the key', () => {
    for (const [local, at] of [
      [[], 'agents.json'],
      [{ default: 3 }, 'default'],
      [{ disabled: 'claude' }, 'disabled'],
      [{ disabled: [3] }, 'disabled'],
      [{ overrides: [] }, 'overrides'],
      [{ entries: 'codex' }, 'entries'],
    ] as const) {
      expect(() => readLocalSettings(local)).toThrowError(LocalSettingsError)
      expect(() => readLocalSettings(local)).toThrowError(new RegExp(`\`${at}\``))
    }
  })
})

describe('mergeAgents', () => {
  it('is the project layer itself when there is no local one', () => {
    expect(mergeAgents(PROJECT, null).agents.map((agent) => agent.source)).toEqual(['project', 'project'])
  })

  /**
   * The snapshot semantics of design-00001 §5 rest on this: a session keeps the
   * entry it was admitted with, so the list must hand out a **new** object each
   * time rather than a shared one a later save could reach into.
   */
  it('builds new entry objects on every call', () => {
    const first = mergeAgents(PROJECT, null).agents[0]!
    const second = mergeAgents(PROJECT, null).agents[0]!

    expect(second).toEqual(first)
    expect(second).not.toBe(first)
    expect(second).not.toBe(PROJECT[0])
  })

  it('refuses an override or an added entry that is not a mapping at all', () => {
    expect(() => mergeAgents(PROJECT, { overrides: { claude: 'm2' } })).toThrowError(/`overrides.claude`/)
    expect(() => mergeAgents(PROJECT, { entries: { codex: 7 } })).toThrowError(/`entries.codex`/)
  })

  /**
   * The one null the file admits (design-00001 §13.1): the project entry's
   * headless declaration is taken away, so the entry leaves the ask option set
   * without leaving the list — which is what makes spec-00009-AC-9.3's Given
   * reachable from the panel at all.
   */
  it('takes a project entry’s headless declaration away for a null override', () => {
    const asker: AgentConfig[] = [{ name: 'claude', command: 'first-cli', args: [], cwd: 'docs', headless: HEADLESS }]

    const { agents } = mergeAgents(asker, { overrides: { claude: { headless: null } } })

    expect(agents[0]!.headless).toBeUndefined()
    expect(agents[0]).toMatchObject({ name: 'claude', command: 'first-cli', source: 'overridden' })
  })

  /**
   * Every other null is the layer being ill-formed, not a key being unset: an
   * added entry has no project declaration to take away, so it admits none either.
   */
  it('refuses a null under any other key, naming it', () => {
    for (const [local, at] of [
      [{ overrides: { claude: { model: null } } }, 'overrides.claude.model'],
      [{ overrides: { claude: { args: null } } }, 'overrides.claude.args'],
      [{ entries: { codex: { command: 'codex', headless: null } } }, 'entries.codex.headless'],
    ] as const) {
      expect(() => mergeAgents(PROJECT, local)).toThrowError(LocalSettingsError)
      expect(() => mergeAgents(PROJECT, local)).toThrowError(new RegExp(`\`${at}\``))
    }
  })

  it('refuses a name written as both an override and an added entry', () => {
    expect(() => mergeAgents(PROJECT, { overrides: { codex: {} }, entries: { codex: { command: 'codex' } } })).toThrowError(
      /both an override and an added entry/,
    )
  })

  /**
   * spec-00009-FR-4 末句: a default the project layer has renamed away from is one
   * ignored line, not a broken layer — the same reading an override or a disabled
   * name that points at nothing gets.
   */
  it('ignores a default that points at no entry, saying so', () => {
    const { agents, notices } = mergeAgents(PROJECT, { default: 'old' })

    expect(agents.map((agent) => agent.name)).toEqual(['claude', 'other'])
    expect(notices).toEqual([{ name: 'old', message: 'the default `old` points at no entry' }])
  })

  it('puts the declared default first and marks it as the default', () => {
    const { agents } = mergeAgents(PROJECT, { default: 'other' })

    expect(agents.map((agent) => agent.name)).toEqual(['other', 'claude'])
    expect(agents[0]!.default).toBe(true)
  })
})

describe('EffectiveAgents', () => {
  it('reads the project layer alone when there is no local file', () => {
    const effective = new EffectiveAgents(PROJECT, repoWith())

    expect(effective.current()).toMatchObject({ local: null, notices: [] })
    expect(effective.current().error).toBeUndefined()
    expect(effective.current().agents).toHaveLength(2)
  })

  /**
   * A file the board cannot read at all is the same outcome as one it cannot
   * parse (spec-00009-FR-4): the project layer, and the reason said out loud.
   * A directory where the file should be is the portable way to be unreadable.
   */
  it('falls back to the project layer when the file cannot be read at all', () => {
    const repoRoot = repoWith()
    mkdirSync(join(repoRoot, '.whiteboard', 'agents.json'), { recursive: true })

    const state = new EffectiveAgents(PROJECT, repoRoot).current()

    expect(state.agents.map((agent) => agent.name)).toEqual(['claude', 'other'])
    expect(state.error!.message).toMatch(/could not be read/)
  })

  // spec-00009-FR-6 — a save that got as far as the staging file and no further
  // leaves nothing of itself behind; a directory where the file should be is the
  // portable way to make the rename, and only the rename, fail
  it('clears the staging file when the rename it was written for fails', () => {
    const repoRoot = repoWith()
    const path = join(repoRoot, '.whiteboard', 'agents.json')
    mkdirSync(path, { recursive: true })
    writeFileSync(join(path, 'in-the-way'), 'x')

    expect(() => new EffectiveAgents(PROJECT, repoRoot).save({ default: 'other' })).toThrowError()
    expect(existsSync(`${path}.tmp`)).toBe(false)
  })

  /**
   * The list is re-read at every admission (design-00001 §13.2), so a standing
   * error would say so on every session started: it is warned about when it
   * appears and when it changes, and not once more.
   */
  it('warns once for a standing problem, and again only when it becomes a different one', () => {
    const repoRoot = repoWith('{ broken')
    const effective = new EffectiveAgents(PROJECT, repoRoot)
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    effective.current()
    effective.current()
    writeFileSync(join(repoRoot, '.whiteboard', 'agents.json'), JSON.stringify({ default: 3 }))
    effective.current()

    expect(warn.mock.calls.map(([line]) => line as string)).toEqual([
      expect.stringContaining('not readable JSON'),
      expect.stringContaining('`default` must name one agent'),
    ])
    warn.mockRestore()
  })
})
