import type { AgentEntry, AgentOverride, AgentSource, LocalAgentSettings } from './api.ts'

/** The working directory of an entry only this machine declares — never its own to choose (spec-00009-FR-3). */
export const APPENDED_CWD = 'docs'

/** What an entry with no `cwd` of its own runs in, which is the server's reading of an absent key. */
const DEFAULT_CWD = '.'

/** The placeholder the model fills in wherever it stands (design-00001 §13.4). */
const MODEL_PLACEHOLDER = '{model}'

/** The keys the local layer may replace on a project entry — every one but `cwd` (design-00001 §13.1). */
export const OVERRIDABLE = ['command', 'model', 'args', 'env', 'headless'] as const

export type OverridableKey = (typeof OVERRIDABLE)[number]

/** What one field of the form hands back, by the key it is the field of. */
export type FieldValue = { [K in OverridableKey]: NonNullable<AgentOverride[K]> }

/**
 * One row of the settings panel (design-00002 §18.2). The list is the **union**
 * of the two layers rather than the effective list: an entry the local layer
 * disables is still listed, since being able to enable it again is the whole
 * point of listing it (spec-00009-AC-7.4).
 */
export interface AgentCard {
  name: string
  /** Whether only the local layer declares it; a project entry is overridden, never appended. */
  appended: boolean
  source: AgentSource
  /** The entry as the two layers merge it: project keys under the local layer's. */
  entry: AgentEntry
  /** Which keys the draft local layer replaces; an appended entry overrides nothing. */
  overridden: OverridableKey[]
  disabled: boolean
  isDefault: boolean
}

/**
 * The cards a project layer and a draft local layer make (design-00002 §18.2).
 * Read off the **draft** rather than off the effective list the server sent, so
 * that undoing an override changes the row before the save rather than after it;
 * `order` is only what the rows are sorted by, which is why the default sits
 * first without this function knowing that it is the default.
 */
export function agentCards(
  project: AgentEntry[],
  local: LocalAgentSettings,
  order: readonly string[],
): AgentCard[] {
  const disabled = new Set(local.disabled ?? [])
  const cards: AgentCard[] = project.map((entry) => {
    const override = local.overrides?.[entry.name] ?? {}
    const overridden = OVERRIDABLE.filter((key) => key in override)
    return {
      name: entry.name,
      appended: false,
      source: overridden.length === 0 ? 'project' : 'overridden',
      entry: merged(entry, override),
      overridden,
      disabled: disabled.has(entry.name),
      isDefault: local.default === entry.name,
    }
  })
  for (const [name, entry] of Object.entries(local.entries ?? {})) {
    // A name the project layer also declares is ill-formed and the save says so
    // (design-00001 §13.1); here it is simply the project row, not a second one.
    if (project.some((one) => one.name === name)) continue
    cards.push({
      name,
      appended: true,
      source: 'local',
      entry: { args: [], ...entry, name, cwd: APPENDED_CWD },
      overridden: [],
      disabled: disabled.has(name),
      isDefault: local.default === name,
    })
  }
  return sorted(cards, order)
}

/**
 * A project entry under its local override, read the way the server reads it:
 * `headless: null` is the declaration **taken away**, not one more key with a
 * value (design-00001 §13.1), so the row shows no headless badge and the ask
 * option set is one shorter.
 */
function merged(entry: AgentEntry, override: AgentOverride): AgentEntry {
  const { headless, ...rest } = { ...entry, ...override }
  return headless === null ? rest : { ...rest, headless }
}

/** Effective order first, everything the effective list has no place for after it, and the disabled last. */
function sorted(cards: AgentCard[], order: readonly string[]): AgentCard[] {
  const rank = (card: AgentCard) => {
    const at = order.indexOf(card.name)
    return at === -1 ? order.length : at
  }
  return [...cards].sort((a, b) => Number(a.disabled) - Number(b.disabled) || rank(a) - rank(b))
}

/** Where an entry runs: the project's own directory, or `docs` for one this machine added. */
export function cwdOf(card: AgentCard): string {
  return card.appended ? APPENDED_CWD : (card.entry.cwd ?? DEFAULT_CWD)
}

/**
 * The command a session would actually run (design-00002 §18.2): the argv with
 * every `{model}` filled in, so what the row shows is what starts rather than
 * the template it is built from.
 */
export function commandSummary(entry: AgentEntry): string {
  const model = entry.model
  const args = model === undefined ? entry.args : entry.args.map((one) => one.split(MODEL_PLACEHOLDER).join(model))
  return [entry.command, ...args].join(' ')
}

/**
 * One field's edit, landing where that card's edits belong: an override on a
 * project entry, the entry itself on an appended one. Key-level whole
 * replacement, never a deep merge — writing one `env` pair replaces the whole
 * `env` (design-00001 §13.1).
 */
export function withField<K extends OverridableKey>(
  local: LocalAgentSettings,
  card: AgentCard,
  key: K,
  value: FieldValue[K],
): LocalAgentSettings {
  const table = card.appended ? 'entries' : 'overrides'
  const current = { ...(local[table]?.[card.name] as AgentOverride | undefined) }
  return {
    ...local,
    [table]: { ...local[table], [card.name]: { ...current, [key]: value } },
  } as LocalAgentSettings
}

/**
 * «No headless»: the entry declares none, and so is no longer one an ask can be
 * put to (design-00002 §18.3). Two writings of one thing, because the layers
 * mean different things by it — a project entry's declaration is **taken away**
 * by the one null the file admits, while an appended entry simply does not carry
 * the key (design-00001 §13.1).
 */
export function withoutHeadless(local: LocalAgentSettings, card: AgentCard): LocalAgentSettings {
  if (!card.appended) {
    const override = { ...local.overrides?.[card.name], headless: null }
    return { ...local, overrides: { ...local.overrides, [card.name]: override } }
  }
  const entries = { ...local.entries }
  const entry = { ...entries[card.name]! }
  delete entry.headless
  entries[card.name] = entry
  return { ...local, entries }
}

/**
 * One override undone: the key is deleted, and with the last key the whole entry
 * goes — an empty override object would still read as «the local layer says
 * something about this entry» (design-00001 §13.1).
 */
export function withoutOverride(local: LocalAgentSettings, name: string, key: OverridableKey): LocalAgentSettings {
  const { [key]: _dropped, ...rest } = local.overrides?.[name] ?? {}
  const overrides = { ...local.overrides }
  if (Object.keys(rest).length === 0) delete overrides[name]
  else overrides[name] = rest
  return { ...local, overrides }
}

/** An appended entry added, blank but for its name; its `command` is what the save will ask for. */
export function withEntry(local: LocalAgentSettings, name: string): LocalAgentSettings {
  return { ...local, entries: { ...local.entries, [name]: { command: '' } } }
}

/**
 * An appended entry deleted (spec-00009-FR-7). The default is let go of with it:
 * a `default` naming an entry that is gone points at nothing, and the save would
 * answer with a notice the user never asked for.
 */
export function withoutEntry(local: LocalAgentSettings, name: string): LocalAgentSettings {
  const entries = { ...local.entries }
  delete entries[name]
  const next: LocalAgentSettings = { ...local, entries }
  if (next.default === name) delete next.default
  return next
}

/** An entry kept out of the effective list, or let back into it (spec-00009-FR-7). */
export function withDisabled(local: LocalAgentSettings, name: string, off: boolean): LocalAgentSettings {
  const disabled = (local.disabled ?? []).filter((one) => one !== name)
  return { ...local, disabled: off ? [...disabled, name] : disabled }
}

/** The one entry the local layer puts first (design-00001 §13.1); naming another replaces it. */
export function withDefault(local: LocalAgentSettings, name: string): LocalAgentSettings {
  return { ...local, default: name }
}

/** The entry and key a refusal's `at` is about, when it is about one (design-00002 §18.3). */
export function fieldAt(at: string | undefined): { name: string; key: string } | undefined {
  const parts = at?.split('.') ?? []
  if (parts.length < 3 || (parts[0] !== 'overrides' && parts[0] !== 'entries')) return undefined
  return { name: parts[1]!, key: parts[2]! }
}
