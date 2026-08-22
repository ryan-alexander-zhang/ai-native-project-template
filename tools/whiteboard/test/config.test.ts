import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ConfigError, findRepoRoot, loadFlowConfig, parseFlowConfig } from '../src/config.ts'

const VALID = `
types:
  idea: { kind: living }
  prd: { kind: living }
  spec: { kind: living }
  plan: { kind: work }
relations: [parent, implements, informs]
flow:
  idea:
    - { next: prd, carry: parent }
    - { next: spec, carry: parent }
  spec:
    - { next: plan, carry: implements }
focus:
  idea: is it worth doing, and for whom
  prd: roles, scope, and the value trade-off
  spec: FR boundaries and acceptance gaps
agents:
  claude:
    command: claude
    args: ["--foo"]
    cwd: docs
`

function parse(text: string) {
  return parseFlowConfig(text, 'test.yaml')
}

function expectConfigError(text: string, match: RegExp) {
  expect(() => parse(text)).toThrowError(ConfigError)
  expect(() => parse(text)).toThrowError(match)
}

describe('parseFlowConfig', () => {
  it('reads types, relations, flow, and agents', () => {
    const config = parse(VALID)
    expect(config.types).toEqual({ idea: 'living', prd: 'living', spec: 'living', plan: 'work' })
    expect(config.relations).toContain('implements')
    expect(config.flow.idea).toEqual([
      { next: 'prd', carry: 'parent' },
      { next: 'spec', carry: 'parent' },
    ])
    expect(config.agents).toEqual([{ name: 'claude', command: 'claude', args: ['--foo'], cwd: 'docs' }])
  })

  it('defaults agent args to an empty list and leaves cwd unset', () => {
    const config = parse(VALID.replace('    args: ["--foo"]\n    cwd: docs\n', ''))
    expect(config.agents[0]).toEqual({ name: 'claude', command: 'claude', args: [], cwd: undefined })
  })

  // spec-00001-AC-15.2 — invalid entries are named in the error
  it('rejects a flow step naming an unknown type', () => {
    expectConfigError(VALID.replace('next: prd, carry: parent', 'next: nope, carry: parent'), /flow\.idea\[0\]\.next.*"nope"/)
  })

  it('rejects a flow step naming an unknown relation', () => {
    expectConfigError(VALID.replace('next: prd, carry: parent', 'next: prd, carry: nope'), /flow\.idea\[0\]\.carry.*"nope"/)
  })

  it('rejects a flow source of an unknown type', () => {
    expectConfigError(VALID.replace('\n  idea:\n', '\n  nope:\n'), /flow\.nope/)
  })

  it('rejects a flow entry that is not a list', () => {
    expectConfigError(VALID.replace(/  spec:\n    - \{ next: plan, carry: implements \}/, '  spec: plan'), /flow\.spec.*list of steps/)
  })

  it('rejects a flow step that is not a mapping', () => {
    expectConfigError(VALID.replace('- { next: plan, carry: implements }', '- plan'), /flow\.spec\[0\].*mapping/)
  })

  it('rejects an unknown document kind', () => {
    expectConfigError(VALID.replace('idea: { kind: living }', 'idea: { kind: eternal }'), /types\.idea\.kind.*"eternal"/)
  })

  it('rejects a type entry that is not a mapping', () => {
    expectConfigError(VALID.replace('idea: { kind: living }', 'idea: living'), /types\.idea.*mapping/)
  })

  it('rejects empty types', () => {
    expectConfigError(VALID.replace(/types:\n(  \w+: \{ kind: \w+ \}\n)+/, 'types: {}\n'), /types.*at least one/)
  })

  it('rejects types that is not a mapping', () => {
    expectConfigError(VALID.replace(/types:\n(  \w+: \{ kind: \w+ \}\n)+/, 'types: [idea]\n'), /`types` must be a mapping/)
  })

  it('rejects relations that are not strings', () => {
    expectConfigError(VALID.replace('relations: [parent, implements, informs]', 'relations: [1, 2]'), /relations.*list of strings/)
  })

  it('rejects empty relations', () => {
    expectConfigError(VALID.replace('relations: [parent, implements, informs]', 'relations: []'), /relations.*at least one/)
  })

  it('rejects a missing agent command', () => {
    expectConfigError(VALID.replace('    command: claude\n', '    command: "  "\n'), /agents\.claude\.command.*non-empty/)
  })

  it('rejects agent args that are not strings', () => {
    expectConfigError(VALID.replace('args: ["--foo"]', 'args: [3]'), /agents\.claude\.args.*list of strings/)
  })

  it('rejects an agent cwd outside docs', () => {
    expectConfigError(VALID.replace('cwd: docs', 'cwd: src'), /agents\.claude\.cwd.*"src"/)
  })

  it('rejects an agent cwd escaping docs', () => {
    expectConfigError(VALID.replace('cwd: docs', 'cwd: docs/../src'), /agents\.claude\.cwd/)
  })

  it('rejects an agent entry that is not a mapping', () => {
    expectConfigError(VALID.replace(/  claude:\n(    .*\n)+/, '  claude: claude\n'), /`agents\.claude` must be a mapping/)
  })

  it('rejects empty agents', () => {
    expectConfigError(VALID.replace(/agents:\n(  claude:\n)(    .*\n)+/, 'agents: {}\n'), /agents.*at least one/)
  })

  it('rejects invalid YAML', () => {
    expectConfigError('types: [\n  unclosed', /invalid YAML/)
  })

  it('rejects a non-mapping root', () => {
    expectConfigError('- a\n- b', /`config root` must be a mapping/)
  })
})

/**
 * spec-00001-FR-48: the flow config carries one focus line per clarifiable type,
 * and the clarifiable set it is checked against is built into the code
 * (rule-00001-BR-20). Every refusal below names the type it is about — that name
 * is the whole point of the check.
 */
describe('the focus lines', () => {
  it('reads a focus line for every clarifiable type the config declares', () => {
    expect(parse(VALID).focus).toEqual({
      idea: 'is it worth doing, and for whom',
      prd: 'roles, scope, and the value trade-off',
      spec: 'FR boundaries and acceptance gaps',
    })
  })

  // spec-00001-AC-48.2
  it('rejects an empty focus line, naming the type', () => {
    expectConfigError(VALID.replace('  spec: FR boundaries and acceptance gaps', '  spec: ""'), /focus\.spec/)
  })

  it('rejects a focus line of nothing but whitespace, naming the type', () => {
    expectConfigError(VALID.replace('  spec: FR boundaries and acceptance gaps', '  spec: "   "'), /focus\.spec/)
  })

  // spec-00001-AC-48.6
  it('rejects a focus line carrying a newline, naming the type', () => {
    expectConfigError(
      VALID.replace('  spec: FR boundaries and acceptance gaps', '  spec: "boundaries\\nand gaps"'),
      /focus\.spec.*one non-empty line/,
    )
  })

  it('rejects a focus line that is not a string, naming the type', () => {
    expectConfigError(VALID.replace('  spec: FR boundaries and acceptance gaps', '  spec: 3'), /focus\.spec.*got 3/)
  })

  // spec-00001-AC-48.4
  it('rejects a config missing the focus line of a clarifiable type, naming it', () => {
    expectConfigError(VALID.replace('  idea: is it worth doing, and for whom\n', ''), /focus\.idea.*is missing/)
  })

  it('rejects a config with no focus lines at all, naming the first type it wants one for', () => {
    expectConfigError(VALID.replace(/focus:\n(  \w+: [^\n]+\n)+/, ''), /focus\.idea.*is missing/)
  })

  // spec-00001-AC-48.5
  it('rejects a focus line given to a type that is not clarifiable, naming that type', () => {
    expectConfigError(
      VALID.replace('focus:\n', 'focus:\n  record: what the evidence was\n'),
      /focus\.record.*not a clarifiable type/,
    )
  })

  it('rejects focus that is not a mapping', () => {
    expectConfigError(VALID.replace(/focus:\n(  \w+: [^\n]+\n)+/, 'focus: [idea]\n'), /`focus` must be a mapping/)
  })

  it('wants no focus line for a clarifiable type the config does not declare', () => {
    // rule and design are clarifiable, and this config declares neither.
    expect(Object.keys(parse(VALID).focus)).toEqual(['idea', 'prd', 'spec'])
  })
})

/**
 * The flow entry types (rule-00001-BR-26, spec-00001-FR-53): the list the create
 * entry is drawn from. It is optional, and every name in it must be a declared
 * type — the two readings AC-53.5 and AC-53.6 fix.
 */
describe('the entry types', () => {
  it('reads the entry list', () => {
    expect(parse(VALID.replace('relations:', 'entry: [idea, prd]\nrelations:')).entry).toEqual(['idea', 'prd'])
  })

  // spec-00001-AC-53.6 — no entry list, and no create entry: a legal config
  it('reads a missing entry list as no entry types at all', () => {
    expect(parse(VALID).entry).toEqual([])
  })

  it('reads an empty entry list the same way', () => {
    expect(parse(VALID.replace('relations:', 'entry: []\nrelations:')).entry).toEqual([])
  })

  // spec-00001-AC-53.5 — the offending type is named
  it('rejects an entry type the config does not declare, naming it', () => {
    expectConfigError(VALID.replace('relations:', 'entry: [idea, report]\nrelations:'), /`entry`.*"report"/)
  })

  it('rejects an entry list that is not a list of strings', () => {
    expectConfigError(VALID.replace('relations:', 'entry: idea\nrelations:'), /`entry` must be a list of strings/)
    expectConfigError(VALID.replace('relations:', 'entry: [3]\nrelations:'), /`entry` must be a list of strings/)
  })
})

/**
 * The relation matrix (spec-00002-FR-5 and FR-6): the startup check of
 * spec-00001-FR-15 extended to `carries`. Missing is a legal reading — the
 * check is then off — so the whole block is opt-in, and so is each type in it.
 */
describe('the relation matrix', () => {
  /** `VALID` with a `carries` block spliced in before `flow`. */
  function withCarries(block: string): string {
    return VALID.replace('flow:', `carries:\n${block}flow:`)
  }

  it('reads a type to the relation fields it carries', () => {
    expect(parse(withCarries('  spec: [parent]\n  prd: [parent, informs]\n')).carries).toEqual({
      spec: ['parent'],
      prd: ['parent', 'informs'],
    })
  })

  // spec-00002-FR-5: an empty list is a reading of its own — this type carries nothing
  it('keeps an empty list apart from a type that is not listed', () => {
    const carries = parse(withCarries('  idea: []\n')).carries
    expect(carries.idea).toEqual([])
    expect('prd' in carries).toBe(false)
  })

  // spec-00002-AC-6.4 — the configs already in the field carry no matrix
  it('reads a missing matrix as no matrix at all, and still starts', () => {
    expect(parse(VALID).carries).toEqual({})
  })

  it('reads an empty matrix the same way', () => {
    expect(parse(VALID.replace('flow:', 'carries:\nflow:')).carries).toEqual({})
  })

  // spec-00002-AC-6.1
  it('rejects a matrix entry for a type the config does not declare, naming it', () => {
    expectConfigError(withCarries('  report: [informs]\n'), /`carries\.report`.*unknown type "report"/)
  })

  // spec-00002-AC-6.2
  it('rejects a relation field the config does not declare, naming it', () => {
    expectConfigError(withCarries('  spec: [parent, verifies]\n'), /`carries\.spec`.*unknown relation "verifies"/)
  })

  // spec-00002-AC-6.3 — a bare string is the case this catches
  it('rejects a matrix value that is not a list of strings, naming the type', () => {
    expectConfigError(withCarries('  spec: parent\n'), /`carries\.spec` must be a list of strings/)
    expectConfigError(withCarries('  spec: [3]\n'), /`carries\.spec` must be a list of strings/)
  })

  it('rejects a matrix that is not a mapping', () => {
    expectConfigError(VALID.replace('flow:', 'carries: [spec]\nflow:'), /`carries` must be a mapping/)
  })
})

describe('loadFlowConfig', () => {
  it('loads a config file from disk', () => {
    const dir = mkdtempSync(join(tmpdir(), 'wb-config-'))
    const path = join(dir, 'whiteboard.config.yaml')
    writeFileSync(path, VALID)
    expect(loadFlowConfig(path).types.idea).toBe('living')
  })

  // spec-00001-AC-15.1 — the missing path is named
  it('rejects a missing config file, naming the path', () => {
    const path = join(mkdtempSync(join(tmpdir(), 'wb-config-')), 'whiteboard.config.yaml')
    expect(() => loadFlowConfig(path)).toThrowError(ConfigError)
    expect(() => loadFlowConfig(path)).toThrowError(new RegExp(`no flow config at ${path.replace(/[/\\]/g, '\\$&')}`))
  })
})

describe('findRepoRoot', () => {
  it('finds the directory that owns the flow config', () => {
    const root = mkdtempSync(join(tmpdir(), 'wb-root-'))
    writeFileSync(join(root, 'whiteboard.config.yaml'), VALID)

    expect(findRepoRoot(root)).toBe(resolve(root))
  })

  it('walks up from a subdirectory so the board starts from anywhere in the repo', () => {
    const root = resolve(mkdtempSync(join(tmpdir(), 'wb-root-')))
    writeFileSync(join(root, 'whiteboard.config.yaml'), VALID)
    const nested = join(root, 'tools', 'whiteboard')
    mkdirSync(nested, { recursive: true })

    expect(findRepoRoot(nested)).toBe(root)
  })

  // spec-00001-AC-15.1 — the path it looked for is named
  it('refuses when no directory up the tree owns a flow config', () => {
    const orphan = mkdtempSync(join(tmpdir(), 'wb-orphan-'))

    expect(() => findRepoRoot(orphan)).toThrowError(ConfigError)
    expect(() => findRepoRoot(orphan)).toThrowError(
      new RegExp(`${join(resolve(orphan), 'whiteboard.config.yaml').replace(/[/\\]/g, '\\$&')}.*or any parent`),
    )
  })
})

describe('the config shipped with this repo', () => {
  it('loads and matches rule-00001 product flow', () => {
    const config = loadFlowConfig(new URL('../../../whiteboard.config.yaml', import.meta.url).pathname)
    // rule-00001-AC-1.1 and AC-1.2: the kind split the board runs on
    expect(config.types.idea).toBe('living')
    expect(config.types.prd).toBe('living')
    expect(config.types.spec).toBe('living')
    expect(config.types.issue).toBe('work')
    expect(config.types.plan).toBe('work')
    expect(config.types.task).toBe('work')
    expect(config.flow.idea?.map((step) => step.next)).toEqual(['prd', 'spec'])
    expect(config.flow.prd?.map((step) => step.next)).toEqual(['spec'])
    expect(config.flow.spec).toEqual([
      { next: 'rule', carry: 'informs' },
      { next: 'design', carry: 'informs' },
      { next: 'plan', carry: 'implements' },
    ])
    // rule-00001-AC-16.1 … AC-16.3: the implementation phase's three next steps,
    // each carrying the relation docs/README.md already gives it
    expect(config.flow.plan).toEqual([
      { next: 'task', carry: 'parent' },
      { next: 'issue', carry: 'blocks' },
      { next: 'record', carry: 'parent' },
    ])
    expect(config.flow.record).toBeUndefined()
  })

  /**
   * spec-00001-AC-48.3 at the config level, and the fixture AC-48.1 needs: every
   * clarifiable type carries its own line, and no two of them say the same thing
   * — a focus line that is not about its own type buys nothing.
   */
  it('carries one distinct focus line for each of the five clarifiable types', () => {
    const config = loadFlowConfig(new URL('../../../whiteboard.config.yaml', import.meta.url).pathname)

    expect(Object.keys(config.focus)).toEqual(['idea', 'prd', 'spec', 'rule', 'design'])
    expect(new Set(Object.values(config.focus)).size).toBe(5)
    for (const line of Object.values(config.focus)) expect(line.trim()).toBe(line)
  })

  /**
   * rule-00001-BR-26: the four flow entry types this repo opens the create entry
   * to, in two segments — idea and prd are the product flow's starters, the two
   * ways docs/README.md says a project enters the flow; design and analysis are
   * the upstream-less thinking carriers the rule's 第十四轮 revision added, which
   * may exist before any spec and so need no advance to produce them.
   */
  it('declares four flow entry types: the two product-flow starters and the two thinking carriers', () => {
    expect(loadFlowConfig(new URL('../../../whiteboard.config.yaml', import.meta.url).pathname).entry).toEqual([
      'idea',
      'prd',
      'design',
      'analysis',
    ])
  })

  /**
   * spec-00002-FR-5: the matrix carries every folder README's "Relations"
   * section, so every declared type is in it — a type left out would silently
   * stop being checked. `supersedes` is in none of the lists on purpose: it is
   * allowed to every type and never looked up (design-00001 §2).
   */
  it('gives every declared type a relation matrix entry', () => {
    const config = loadFlowConfig(new URL('../../../whiteboard.config.yaml', import.meta.url).pathname)

    expect(Object.keys(config.carries).sort()).toEqual(Object.keys(config.types).sort())
    expect(config.carries.idea).toEqual([])
    expect(config.carries.prompt).toEqual([])
    expect(Object.values(config.carries).flat()).not.toContain('supersedes')
  })

  /**
   * The declaration order of `types` is the board's column order
   * (decision-00002 §2). Nothing else in the code depends on it, so without
   * this test a tidy-up reorder would silently rearrange the whiteboard.
   */
  it('declares the types in the column order the board reads left to right', () => {
    const config = loadFlowConfig(new URL('../../../whiteboard.config.yaml', import.meta.url).pathname)

    expect(Object.keys(config.types)).toEqual([
      'idea',
      'prd',
      'analysis',
      'reference',
      'integration',
      'spec',
      'rule',
      'decision',
      'design',
      'plan',
      'task',
      'issue',
      'record',
      'report',
      'operation',
      'prompt',
    ])
  })
})
