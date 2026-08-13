import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ConfigError, loadFlowConfig, parseFlowConfig } from '../src/config.ts'

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

describe('the config shipped with this repo', () => {
  it('loads and matches rule-00001 product flow', () => {
    const config = loadFlowConfig(new URL('../../../whiteboard.config.yaml', import.meta.url).pathname)
    expect(config.types.idea).toBe('living')
    expect(config.types.plan).toBe('work')
    expect(config.flow.idea?.map((step) => step.next)).toEqual(['prd', 'spec'])
    expect(config.flow.prd?.map((step) => step.next)).toEqual(['spec'])
    expect(config.flow.spec).toEqual([
      { next: 'rule', carry: 'informs' },
      { next: 'design', carry: 'informs' },
      { next: 'plan', carry: 'implements' },
    ])
    expect(config.flow.plan).toEqual([{ next: 'task', carry: 'parent' }])
    expect(config.flow.record).toBeUndefined()
  })
})
