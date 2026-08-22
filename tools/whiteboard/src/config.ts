import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { parse as parseYaml } from 'yaml'
import { clarifiableTypes, isClarifiable } from './clarifyRules.ts'

export const CONFIG_FILE = 'whiteboard.config.yaml'

export type DocKind = 'living' | 'work'

export interface FlowStep {
  next: string
  carry: string
}

export interface AgentConfig {
  name: string
  command: string
  args: string[]
  cwd?: string
}

export interface FlowConfig {
  types: Record<string, DocKind>
  relations: string[]
  flow: Record<string, FlowStep[]>
  /** Clarifiable type -> its focus line (spec-00001-FR-48). */
  focus: Record<string, string>
  /** Flow entry types the create entry offers (rule-00001-BR-26); empty = no create entry (spec-00001-FR-53). */
  entry: string[]
  /**
   * The relation matrix of spec-00002-FR-5: type -> the relation fields a
   * document of that type may declare. A type absent from it is not checked, so
   * an absent matrix reads as this record being empty; a type present with an
   * empty list carries no relation field at all. The two readings differ, which
   * is why the distinction is the presence of the key.
   */
  carries: Record<string, string[]>
  agents: AgentConfig[]
}

/** Startup-blocking configuration problem; the message always names the offending entry. */
export class ConfigError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ConfigError'
  }
}

const KINDS: readonly string[] = ['living', 'work']

function asRecord(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new ConfigError(`config: \`${field}\` must be a mapping`)
  }
  return value as Record<string, unknown>
}

function readTypes(raw: unknown): Record<string, DocKind> {
  const entries = Object.entries(asRecord(raw, 'types'))
  if (entries.length === 0) {
    throw new ConfigError('config: `types` must declare at least one document type')
  }
  const types: Record<string, DocKind> = {}
  for (const [name, value] of entries) {
    const kind = asRecord(value, `types.${name}`).kind
    if (typeof kind !== 'string' || !KINDS.includes(kind)) {
      throw new ConfigError(`config: \`types.${name}.kind\` must be "living" or "work", got ${JSON.stringify(kind)}`)
    }
    types[name] = kind as DocKind
  }
  return types
}

function readRelations(raw: unknown): string[] {
  if (!Array.isArray(raw) || raw.some((item) => typeof item !== 'string')) {
    throw new ConfigError('config: `relations` must be a list of strings')
  }
  if (raw.length === 0) {
    throw new ConfigError('config: `relations` must declare at least one relation field')
  }
  return raw as string[]
}

function readFlowStep(value: unknown, at: string, types: Record<string, DocKind>, relations: string[]): FlowStep {
  const step = asRecord(value, at)
  const { next, carry } = step
  if (typeof next !== 'string' || !(next in types)) {
    throw new ConfigError(`config: \`${at}.next\` names unknown type ${JSON.stringify(next)}`)
  }
  if (typeof carry !== 'string' || !relations.includes(carry)) {
    throw new ConfigError(`config: \`${at}.carry\` names unknown relation ${JSON.stringify(carry)}`)
  }
  return { next, carry }
}

function readFlow(raw: unknown, types: Record<string, DocKind>, relations: string[]): Record<string, FlowStep[]> {
  const flow: Record<string, FlowStep[]> = {}
  for (const [from, value] of Object.entries(asRecord(raw, 'flow'))) {
    if (!(from in types)) {
      throw new ConfigError(`config: \`flow.${from}\` names unknown type ${JSON.stringify(from)}`)
    }
    if (!Array.isArray(value)) {
      throw new ConfigError(`config: \`flow.${from}\` must be a list of steps`)
    }
    flow[from] = value.map((step, i) => readFlowStep(step, `flow.${from}[${i}]`, types, relations))
  }
  return flow
}

/**
 * The focus lines of spec-00001-FR-48: the questioning weight of each clarifiable
 * type, and the only part of the clarify instruction the config owns (the shared
 * skeleton is code). The clarifiable set is built in (rule-00001-BR-20), so this
 * mapping is checked against it in both directions — every clarifiable type the
 * config declares must carry a line, and a line may name no other type. A line
 * is one non-empty line: blank, non-string or multi-line is refused, naming the
 * type it belongs to.
 */
function readFocus(raw: unknown, types: Record<string, DocKind>): Record<string, string> {
  // No `focus` key at all is the same reading as an empty one: each clarifiable
  // type it fails to carry is then reported by name (spec-00001-AC-48.4).
  const entries = Object.entries(raw === undefined || raw === null ? {} : asRecord(raw, 'focus'))
  const focus: Record<string, string> = {}
  for (const [type, value] of entries) {
    if (!isClarifiable(type)) {
      throw new ConfigError(`config: \`focus.${type}\` gives a focus line to ${type}, which is not a clarifiable type`)
    }
    if (typeof value !== 'string' || value.trim() === '' || /[\r\n]/.test(value)) {
      throw new ConfigError(`config: \`focus.${type}\` must be one non-empty line, got ${JSON.stringify(value)}`)
    }
    focus[type] = value
  }
  for (const type of clarifiableTypes()) {
    if (type in types && !(type in focus)) {
      throw new ConfigError(`config: \`focus.${type}\` is missing; every clarifiable type needs a focus line`)
    }
  }
  return focus
}

/**
 * The flow entry types (rule-00001-BR-26, spec-00001-FR-53): the only types the
 * board may create a document of. A missing or empty list is a legal reading —
 * the board starts and offers no create entry (spec-00001-AC-53.6) — so the one
 * thing checked here is that every name is a declared type, named in the error
 * when it is not (AC-53.5).
 */
function readEntry(raw: unknown, types: Record<string, DocKind>): string[] {
  if (raw === undefined || raw === null) return []
  if (!Array.isArray(raw) || raw.some((item) => typeof item !== 'string')) {
    throw new ConfigError('config: `entry` must be a list of strings')
  }
  for (const name of raw as string[]) {
    if (!(name in types)) {
      throw new ConfigError(`config: \`entry\` names unknown type ${JSON.stringify(name)}`)
    }
  }
  return raw as string[]
}

/**
 * The relation matrix (spec-00002-FR-5 and FR-6): which relation fields each
 * type may declare, the one thing that answers it — the board never reads the
 * prose in a folder README. Missing, null or empty is a legal reading and the
 * check is simply off for everything (spec-00002-AC-6.4): the configs already
 * in the field carry no matrix, and backward compatibility beats completeness.
 *
 * `supersedes` is deliberately not required of any list: docs/README.md grants
 * it to every type, so it is allowed globally and never looked up here
 * (design-00001 §2 治理轮裁定).
 */
function readCarries(raw: unknown, types: Record<string, DocKind>, relations: string[]): Record<string, string[]> {
  if (raw === undefined || raw === null) return {}
  const carries: Record<string, string[]> = {}
  for (const [type, value] of Object.entries(asRecord(raw, 'carries'))) {
    if (!(type in types)) {
      throw new ConfigError(`config: \`carries.${type}\` names unknown type ${JSON.stringify(type)}`)
    }
    if (!Array.isArray(value) || value.some((field) => typeof field !== 'string')) {
      throw new ConfigError(`config: \`carries.${type}\` must be a list of strings`)
    }
    for (const field of value as string[]) {
      if (!relations.includes(field)) {
        throw new ConfigError(`config: \`carries.${type}\` names unknown relation ${JSON.stringify(field)}`)
      }
    }
    carries[type] = value as string[]
  }
  return carries
}

function readAgentCwd(value: unknown, at: string): string | undefined {
  if (value === undefined || value === null) return undefined
  if (typeof value !== 'string' || (value !== 'docs' && !value.startsWith('docs/')) || value.includes('..')) {
    throw new ConfigError(`config: \`${at}.cwd\` must be "docs" or a path inside it, got ${JSON.stringify(value)}`)
  }
  return value
}

function readAgent(name: string, value: unknown): AgentConfig {
  const at = `agents.${name}`
  const agent = asRecord(value, at)
  if (typeof agent.command !== 'string' || agent.command.trim() === '') {
    throw new ConfigError(`config: \`${at}.command\` must be a non-empty string`)
  }
  const args = agent.args ?? []
  if (!Array.isArray(args) || args.some((arg) => typeof arg !== 'string')) {
    throw new ConfigError(`config: \`${at}.args\` must be a list of strings`)
  }
  return { name, command: agent.command, args: args as string[], cwd: readAgentCwd(agent.cwd, at) }
}

function readAgents(raw: unknown): AgentConfig[] {
  const entries = Object.entries(asRecord(raw, 'agents'))
  if (entries.length === 0) {
    throw new ConfigError('config: `agents` must declare at least one agent')
  }
  return entries.map(([name, value]) => readAgent(name, value))
}

/** Parse and validate flow config text. `source` only labels errors. */
export function parseFlowConfig(text: string, source: string): FlowConfig {
  let raw: unknown
  try {
    raw = parseYaml(text)
  } catch (cause) {
    throw new ConfigError(`config ${source}: invalid YAML — ${(cause as Error).message}`)
  }
  const root = asRecord(raw, 'config root')
  const types = readTypes(root.types)
  const relations = readRelations(root.relations)
  return {
    types,
    relations,
    flow: readFlow(root.flow, types, relations),
    focus: readFocus(root.focus, types),
    entry: readEntry(root.entry, types),
    carries: readCarries(root.carries, types, relations),
    agents: readAgents(root.agents),
  }
}

/**
 * The repo the board runs on: the nearest directory at or above `start` that owns
 * a flow config. Walking up is what lets the board start from any subdirectory.
 */
export function findRepoRoot(start: string): string {
  let dir = resolve(start)
  for (;;) {
    if (existsSync(join(dir, CONFIG_FILE))) return dir
    const parent = dirname(dir)
    if (parent === dir) {
      throw new ConfigError(`config: no flow config at ${join(resolve(start), CONFIG_FILE)} or any parent directory`)
    }
    dir = parent
  }
}

/** Load the flow config from disk. A missing or invalid file is fatal — there is no built-in default. */
export function loadFlowConfig(path: string): FlowConfig {
  let text: string
  try {
    text = readFileSync(path, 'utf8')
  } catch {
    throw new ConfigError(`config: no flow config at ${path}`)
  }
  return parseFlowConfig(text, path)
}
