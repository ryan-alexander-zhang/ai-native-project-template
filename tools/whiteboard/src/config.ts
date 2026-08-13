import { readFileSync } from 'node:fs'
import { parse as parseYaml } from 'yaml'

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
  return { types, relations, flow: readFlow(root.flow, types, relations), agents: readAgents(root.agents) }
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
