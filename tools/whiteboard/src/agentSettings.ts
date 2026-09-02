import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { type AgentConfig, type ConfigError, readAgentEntry } from './config.ts'

/** Where the local layer lives: under the board's own run-time home, which git already ignores (design-00001 §13.1). */
export const LOCAL_SETTINGS_FILE = join('.whiteboard', 'agents.json')

/**
 * The local agent settings (spec-00009-FR-3, design-00001 §13.1): the layer the
 * settings panel writes, over the project layer the repo carries. Four optional
 * keys — `overrides` and `entries` are kept apart rather than merged into one
 * table so that «this override points at a project entry that is gone» is one
 * key comparison, and «an added entry may declare no `cwd`» one key check.
 */
export interface LocalAgentSettings {
  /** The entry to put first, and so the one an unnamed session runs (spec-00009-FR-3). */
  default?: string
  /** Entries kept out of the effective list altogether; naming one at start-up is refused as unknown. */
  disabled?: string[]
  /** Per-project-entry key replacements — never `cwd`, which is the write-scope barrier. */
  overrides?: Record<string, unknown>
  /** Entries this machine alone declares; their `cwd` is always `docs`. */
  entries?: Record<string, unknown>
}

/** Where one effective entry came from, which is what the settings panel labels it with (spec-00009-FR-7). */
export type AgentSource = 'project' | 'local' | 'overridden'

export interface EffectiveAgent extends AgentConfig {
  source: AgentSource
  /** Whether the local layer named this one the default, rather than it merely being first. */
  default: boolean
}

/** One thing the local layer says that points at nothing; the layer itself still holds (spec-00009-FR-4 末句). */
export interface AgentNotice {
  name: string
  message: string
}

/**
 * The whole local layer is ill-formed (spec-00009-FR-4). Never fatal: the board
 * starts, the effective list falls back to the project layer, and the panel is
 * told why. Ill-formed and not partly applied — a configuration half in force is
 * harder to explain than one that is not in force at all (decision-00017 §2 第 6 条).
 */
export class LocalSettingsError extends Error {
  /** The key the refusal is at, as a dotted path — `overrides.claude.model`, say. */
  readonly at?: string

  constructor(message: string, at?: string) {
    super(message)
    this.name = 'LocalSettingsError'
    this.at = at
  }
}

/** What the board reads off the two layers at one moment (design-00001 §13.2). */
export interface EffectiveState {
  agents: EffectiveAgent[]
  notices: AgentNotice[]
  /** Why the local layer is being ignored, when it is (spec-00009-FR-4). */
  error?: { message: string; at?: string }
  /** The local file as it stands, for the panel to edit; null when there is none the board could read. */
  local: LocalAgentSettings | null
}

function mapping(value: unknown, at: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new LocalSettingsError(`agent settings: \`${at}\` must be a mapping`, at)
  }
  return value as Record<string, unknown>
}

/**
 * The four keys of the file, checked as shapes alone (design-00001 §13.3 ①).
 * `default` being a single string is what makes «more than one default»
 * structurally impossible rather than a rule to enforce.
 */
export function readLocalSettings(raw: unknown): LocalAgentSettings {
  const root = mapping(raw, 'agents.json')
  const { default: chosen, disabled, overrides, entries } = root
  if (chosen !== undefined && typeof chosen !== 'string') {
    throw new LocalSettingsError('agent settings: `default` must name one agent', 'default')
  }
  if (disabled !== undefined && (!Array.isArray(disabled) || disabled.some((name) => typeof name !== 'string'))) {
    throw new LocalSettingsError('agent settings: `disabled` must be a list of agent names', 'disabled')
  }
  return {
    default: chosen as string | undefined,
    disabled: disabled as string[] | undefined,
    overrides: overrides === undefined ? undefined : mapping(overrides, 'overrides'),
    entries: entries === undefined ? undefined : mapping(entries, 'entries'),
  }
}

/** The project layer on its own: what the effective list is when there is no local layer to lay over it. */
function projectOnly(project: AgentConfig[]): EffectiveAgent[] {
  return project.map((agent) => ({ ...agent, source: 'project', default: false }))
}

/**
 * One local entry, checked the way a project one is: the two layers run the
 * **same** function — that is what `readAgentEntry` is for — and differ only in
 * what a failure costs. Here it costs the layer, not the start (spec-00009-FR-4).
 */
function localEntry(name: string, raw: unknown, at: string): AgentConfig {
  try {
    return readAgentEntry(name, raw, at)
  } catch (cause) {
    throw new LocalSettingsError((cause as ConfigError).message, (cause as ConfigError).at)
  }
}

/**
 * Apply the local overrides (design-00001 §13.2 ②). The keys are replaced
 * wholesale and the **result** is then checked, never each layer on its own: a
 * project entry with `{model}` in its `args` and a local override that replaces
 * those `args` with ones that have none is a cross-layer error neither layer
 * would catch alone.
 */
function applyOverrides(
  merged: Map<string, EffectiveAgent>,
  overrides: Record<string, unknown>,
  entries: Record<string, unknown>,
  notices: AgentNotice[],
): void {
  for (const [name, patch] of Object.entries(overrides)) {
    if (name in entries) {
      throw new LocalSettingsError(
        `agent settings: \`${name}\` is both an override and an added entry; an override belongs under \`overrides\` alone`,
        `entries.${name}`,
      )
    }
    const at = `overrides.${name}`
    const keys = mapping(patch, at)
    if ('cwd' in keys) {
      throw new LocalSettingsError(
        `agent settings: \`${at}.cwd\` may not be overridden; the working directory is the write-scope barrier`,
        `${at}.cwd`,
      )
    }
    const base = merged.get(name)
    if (!base) {
      // The project layer renamed or dropped it. One ignored line, not a broken
      // layer: a `git pull` must not cost the user the rest of their settings.
      notices.push({ name, message: `the override of \`${name}\` points at no project entry` })
      continue
    }
    // The project entry's own keys with the local ones over them; the two fields
    // this map adds are simply not keys an entry has.
    const entry = localEntry(name, { ...base, ...keys }, at)
    merged.set(name, { ...entry, source: 'overridden', default: false })
  }
}

/**
 * Append what only this machine declares (design-00001 §13.2 ③). `cwd` is filled
 * in rather than read: an added entry running anywhere but `docs/` would let one
 * form put an agent at the repo root, which is the barrier spec-00001-FR-13
 * rests on (decision-00017 §2 第 4 条).
 */
function addEntries(merged: Map<string, EffectiveAgent>, entries: Record<string, unknown>): void {
  for (const [name, value] of Object.entries(entries)) {
    const at = `entries.${name}`
    const keys = mapping(value, at)
    if ('cwd' in keys) {
      throw new LocalSettingsError(
        `agent settings: \`${at}.cwd\` may not be declared; an added entry always runs in \`docs\``,
        `${at}.cwd`,
      )
    }
    if (merged.has(name)) {
      throw new LocalSettingsError(
        `agent settings: \`${name}\` has the same name as a project entry; write it under \`overrides\``,
        at,
      )
    }
    merged.set(name, { ...localEntry(name, { ...keys, cwd: 'docs' }, at), source: 'local', default: false })
  }
}

/** Drop what the local layer disables (design-00001 §13.2 ④); a name nothing answers to is one ignored line. */
function applyDisabled(merged: Map<string, EffectiveAgent>, disabled: string[], notices: AgentNotice[]): void {
  for (const name of disabled) {
    if (!merged.delete(name)) {
      notices.push({ name, message: `\`${name}\` is disabled, but no entry of that name exists` })
    }
  }
}

/**
 * The effective agent list (spec-00009-FR-3): the project layer with the local
 * one laid over it, in the order of design-00001 §13.2. Pure, and it builds new
 * objects every call — a session takes its entry as a snapshot at admission
 * (design-00001 §5), and a shared reference would let a later save reach into a
 * session that had already been received.
 */
export function mergeAgents(
  project: AgentConfig[],
  local: LocalAgentSettings | null,
): { agents: EffectiveAgent[]; notices: AgentNotice[] } {
  const notices: AgentNotice[] = []
  if (!local) return { agents: projectOnly(project), notices }
  const merged = new Map<string, EffectiveAgent>(projectOnly(project).map((agent) => [agent.name, agent]))
  const entries = local.entries ?? {}
  applyOverrides(merged, local.overrides ?? {}, entries, notices)
  addEntries(merged, entries)
  const disabled = local.disabled ?? []
  applyDisabled(merged, disabled, notices)
  const agents = [...merged.values()]
  if (local.default !== undefined) moveDefaultFirst(agents, local.default, disabled, notices)
  if (agents.length === 0) {
    throw new LocalSettingsError('agent settings: the effective agent list would be empty')
  }
  return { agents, notices }
}

/**
 * The declared default goes to the front (design-00001 §13.2 ⑤). Ordering, not
 * a key of its own: «no name means the first» (spec-00001-FR-55) then stays the
 * rule it has always been, and only what it reads changes.
 */
function moveDefaultFirst(
  agents: EffectiveAgent[],
  chosen: string,
  disabled: string[],
  notices: AgentNotice[],
): void {
  if (disabled.includes(chosen)) {
    throw new LocalSettingsError(`agent settings: \`${chosen}\` is the default and also disabled`, 'default')
  }
  const index = agents.findIndex((agent) => agent.name === chosen)
  if (index === -1) {
    notices.push({ name: chosen, message: `the default \`${chosen}\` points at no entry` })
    return
  }
  const [first] = agents.splice(index, 1)
  first!.default = true
  agents.unshift(first!)
}

/**
 * The two layers as the board reads them (design-00001 §13.2 读取点). The file
 * is re-read on **every** call — session admission, config download, the panel's
 * own reads — because the effective list may not be frozen at start-up
 * (decision-00017 §5) and a file the user edits by hand has to take effect
 * without a restart (spec-00009-AC-3.7). It is a few hundred bytes and the call
 * sites are few, so no cache stands between it and the answer.
 */
export class EffectiveAgents {
  private readonly project: AgentConfig[]
  private readonly path: string
  /** The last refusal reported, so a bad file is warned about once rather than once per session. */
  private warned?: string

  constructor(project: AgentConfig[], repoRoot: string) {
    this.project = project
    this.path = join(repoRoot, LOCAL_SETTINGS_FILE)
  }

  current(): EffectiveState {
    let local: LocalAgentSettings | null = null
    try {
      local = this.read()
      const merged = mergeAgents(this.project, local)
      this.warn(undefined)
      return { ...merged, local }
    } catch (cause) {
      const error = { message: (cause as Error).message, at: (cause as LocalSettingsError).at }
      this.warn(error.message)
      return { agents: projectOnly(this.project), notices: [], error, local }
    }
  }

  /**
   * Save a whole new local layer (design-00001 §13.3): the shape, then the merge
   * — an ill-formed one is refused with nothing written (spec-00009-FR-6) — and
   * only then the file, through a temporary name and a rename. The rename is
   * atomic, so what is on disk at any moment is the old file or the new one and
   * never half of either (spec-00009-AC-6.4). Nothing in memory changes, because
   * nothing in memory holds the list: the next reader re-reads the file.
   */
  save(body: unknown): EffectiveState {
    const local = readLocalSettings(body)
    const merged = mergeAgents(this.project, local)
    mkdirSync(dirname(this.path), { recursive: true })
    const staging = `${this.path}.tmp`
    writeFileSync(staging, `${JSON.stringify(local, null, 2)}\n`)
    renameSync(staging, this.path)
    this.warn(undefined)
    return { ...merged, local }
  }

  /** The local layer as it is on disk; no file at all is an empty layer, not a problem to report. */
  private read(): LocalAgentSettings | null {
    let text: string
    try {
      text = readFileSync(this.path, 'utf8')
    } catch (cause) {
      if ((cause as NodeJS.ErrnoException).code === 'ENOENT') return null
      throw new LocalSettingsError(
        `agent settings: ${LOCAL_SETTINGS_FILE} could not be read — ${(cause as Error).message}`,
      )
    }
    let raw: unknown
    try {
      raw = JSON.parse(text)
    } catch (cause) {
      throw new LocalSettingsError(
        `agent settings: ${LOCAL_SETTINGS_FILE} is not readable JSON — ${(cause as Error).message}`,
      )
    }
    return readLocalSettings(raw)
  }

  /** Say it once: the list is re-read per admission, and a bad file would otherwise say so on every one. */
  private warn(message: string | undefined): void {
    if (message !== undefined && message !== this.warned) {
      console.warn(`whiteboard: the local agent settings are ignored — ${message}`)
    }
    this.warned = message
  }
}
