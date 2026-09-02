import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { parse as parseYaml } from 'yaml'
import { clarifiableTypes, isClarifiable } from './clarifyRules.ts'
import {
  CAPTURES,
  type CaptureName,
  type HeadlessConfig,
  MODEL_PLACEHOLDER,
  QUESTION_PLACEHOLDER,
  SESSION_PLACEHOLDER,
} from './headless.ts'

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
  /**
   * The model this entry runs on (spec-00009-FR-1), substituted for every
   * `{model}` in `args` and in the headless declaration. A name the board never
   * reads: which flag carries it is the entry's own business, which is what
   * keeps no CLI's model flag in the code (decision-00017 §5).
   */
  model?: string
  /**
   * What this entry adds to the board's own process environment when it spawns
   * (spec-00009-FR-1). An empty mapping is a legal reading and the same as none.
   */
  env?: Record<string, string>
  /**
   * The agent's non-interactive form, if it declares one (spec-00005-FR-8). An
   * agent without it answers no ask: it is simply out of the choice
   * (spec-00005-FR-2), and the rest of its entry is checked as before.
   */
  headless?: HeadlessConfig
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
  /** How many agent sessions may run at once (spec-00003-FR-3); a missing key reads as {@link DEFAULT_MAX_SESSIONS}. */
  maxSessions: number
  agents: AgentConfig[]
}

/** Startup-blocking configuration problem; the message always names the offending entry. */
export class ConfigError extends Error {
  /**
   * The key the problem is at, as a dotted path. Carried beside the message
   * because the same entry checks run over the local agent settings, whose
   * refusal answers `{error, at}` (design-00001 §13.2).
   */
  readonly at?: string

  constructor(message: string, at?: string) {
    super(message)
    this.name = 'ConfigError'
    this.at = at
  }
}

const KINDS: readonly string[] = ['living', 'work']

/** The session cap a config that declares no `max_sessions` runs with (spec-00003-AC-3.5). */
export const DEFAULT_MAX_SESSIONS = 3

function asRecord(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new ConfigError(`config: \`${field}\` must be a mapping`, field)
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

/**
 * The session cap of spec-00003-FR-3: how many agent sessions may run at once.
 * A missing key is a legal reading — the board starts on the default
 * (spec-00003-AC-3.5) — and anything that is not a positive integer refuses to
 * start, naming the key (spec-00003-AC-3.4).
 */
function readMaxSessions(raw: unknown): number {
  if (raw === undefined || raw === null) return DEFAULT_MAX_SESSIONS
  if (typeof raw !== 'number' || !Number.isInteger(raw) || raw < 1) {
    throw new ConfigError(`config: \`max_sessions\` must be a positive integer, got ${JSON.stringify(raw)}`)
  }
  return raw
}

function readAgentCwd(value: unknown, at: string): string | undefined {
  if (value === undefined || value === null) return undefined
  if (typeof value !== 'string' || (value !== 'docs' && !value.startsWith('docs/')) || value.includes('..')) {
    throw new ConfigError(
      `config: \`${at}.cwd\` must be "docs" or a path inside it, got ${JSON.stringify(value)}`,
      `${at}.cwd`,
    )
  }
  return value
}

function readArgv(value: unknown, at: string): string[] {
  if (!Array.isArray(value) || value.length === 0 || value.some((arg) => typeof arg !== 'string')) {
    throw new ConfigError(`config: \`${at}\` must be a non-empty list of strings`, at)
  }
  return value as string[]
}

/** How many times a placeholder stands in an argv, counted across the whole of it. */
function placeholders(argv: string[], placeholder: string): number {
  return argv.join(' ').split(placeholder).length - 1
}

function requirePlaceholder(argv: string[], placeholder: string, times: number, at: string): void {
  const found = placeholders(argv, placeholder)
  if (found !== times) {
    throw new ConfigError(`config: \`${at}\` must hold ${times} \`${placeholder}\` placeholder, not ${found}`, at)
  }
}

/**
 * The agent's headless declaration (spec-00005-FR-8, design-00001 §10.1),
 * checked in the one startup pass everything else is checked in — an ill-formed
 * one refuses to start and names the entry (spec-00005-AC-8.1). What is checked
 * is what the call cannot be built without: both forms, the question in each of
 * them, the resume id in the resume form and nowhere else, and a capture the
 * code actually holds. A missing declaration is a legal reading — that agent is
 * simply not one an ask can choose.
 */
function readHeadless(value: unknown, at: string): HeadlessConfig | undefined {
  if (value === undefined || value === null) return undefined
  const headless = asRecord(value, at)
  const first = readArgv(headless.first, `${at}.first`)
  const resume = readArgv(headless.resume, `${at}.resume`)
  requirePlaceholder(first, QUESTION_PLACEHOLDER, 1, `${at}.first`)
  requirePlaceholder(resume, QUESTION_PLACEHOLDER, 1, `${at}.resume`)
  requirePlaceholder(first, SESSION_PLACEHOLDER, 0, `${at}.first`)
  requirePlaceholder(resume, SESSION_PLACEHOLDER, 1, `${at}.resume`)
  const { capture } = headless
  if (typeof capture !== 'string' || !(CAPTURES as readonly string[]).includes(capture)) {
    throw new ConfigError(
      `config: \`${at}.capture\` must be one of ${CAPTURES.join(', ')}, got ${JSON.stringify(capture)}`,
      `${at}.capture`,
    )
  }
  return { first, resume, capture: capture as CaptureName }
}

/** The model of one entry (spec-00009-FR-1): free text, since no CLI publishes a list to check it against. */
function readModel(value: unknown, at: string): string | undefined {
  if (value === undefined || value === null) return undefined
  if (typeof value !== 'string' || value.trim() === '') {
    throw new ConfigError(
      `config: \`${at}.model\` must be a non-empty string, got ${JSON.stringify(value)}`,
      `${at}.model`,
    )
  }
  return value
}

/**
 * What the entry adds to the child's environment (spec-00009-FR-1). No key is
 * off limits — `PATH` included: the only person who can write this file is the
 * one whose machine it runs on (spec-00009 §7).
 */
function readEnv(value: unknown, at: string): Record<string, string> | undefined {
  if (value === undefined || value === null) return undefined
  const env = asRecord(value, `${at}.env`)
  for (const [key, item] of Object.entries(env)) {
    if (typeof item !== 'string') {
      throw new ConfigError(
        `config: \`${at}.env.${key}\` must be a string, got ${JSON.stringify(item)}`,
        `${at}.env.${key}`,
      )
    }
  }
  return env as Record<string, string>
}

/**
 * `model` and `{model}` are paired per form (spec-00009-FR-2): a placeholder
 * with no value would spawn the word `{model}` itself, and a value no array
 * stands for is a model that silently never reaches the CLI — which is the very
 * failure the key exists to make visible. «Holds it» is at least once, unlike
 * `{question}`: a model may legitimately be named twice in one command line.
 */
function requireModel(agent: AgentConfig, at: string): void {
  const arrays: Array<[string[], string]> = [[agent.args, `${at}.args`]]
  if (agent.headless) {
    arrays.push([agent.headless.first, `${at}.headless.first`], [agent.headless.resume, `${at}.headless.resume`])
  }
  for (const [argv, where] of arrays) {
    const found = placeholders(argv, MODEL_PLACEHOLDER) > 0
    if (agent.model === undefined && found) {
      throw new ConfigError(
        `config: \`${where}\` holds a \`${MODEL_PLACEHOLDER}\` placeholder, but \`${at}.model\` is not set`,
        `${at}.model`,
      )
    }
    if (agent.model !== undefined && !found) {
      throw new ConfigError(
        `config: \`${at}.model\` is set, so \`${where}\` must hold a \`${MODEL_PLACEHOLDER}\` placeholder`,
        where,
      )
    }
  }
}

/**
 * One agent entry, checked whole (design-00001 §3). The error prefix is the
 * caller's rather than this function's: the project layer is at
 * `agents.<name>` and the local one at `overrides.<name>` / `entries.<name>`,
 * and one function checking both is what makes «one set of rules over the two
 * layers» (spec-00009-FR-3) mechanical rather than a promise.
 */
export function readAgentEntry(name: string, value: unknown, at: string): AgentConfig {
  const agent = asRecord(value, at)
  if (typeof agent.command !== 'string' || agent.command.trim() === '') {
    throw new ConfigError(`config: \`${at}.command\` must be a non-empty string`, `${at}.command`)
  }
  const args = agent.args ?? []
  if (!Array.isArray(args) || args.some((arg) => typeof arg !== 'string')) {
    throw new ConfigError(`config: \`${at}.args\` must be a list of strings`, `${at}.args`)
  }
  const entry: AgentConfig = {
    name,
    command: agent.command,
    args: args as string[],
    cwd: readAgentCwd(agent.cwd, at),
    model: readModel(agent.model, at),
    env: readEnv(agent.env, at),
    headless: readHeadless(agent.headless, `${at}.headless`),
  }
  requireModel(entry, at)
  return entry
}

function readAgents(raw: unknown): AgentConfig[] {
  const entries = Object.entries(asRecord(raw, 'agents'))
  if (entries.length === 0) {
    throw new ConfigError('config: `agents` must declare at least one agent')
  }
  return entries.map(([name, value]) => readAgentEntry(name, value, `agents.${name}`))
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
    maxSessions: readMaxSessions(root.max_sessions),
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
