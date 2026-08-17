import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { CONFIG_FILE, findRepoRoot, loadFlowConfig } from '../src/config.ts'
import { readGraph } from '../src/docRepository.ts'

/**
 * The contract test of spec-00001-FR-41 and decision-00005 §2 第 3 条: this
 * repo's own `docs/` tree, read by the board's own parser, against the item
 * grammar the folder READMEs publish. It is the one assertion where the
 * grammar, the parser, and the real documents have to agree — a red run means
 * one of the three moved without the other two, and which one it was is the
 * first question to answer, not a threshold to raise.
 *
 * `draft` documents are not exempt (decision-00005 §4): the gate guards the
 * finish, the board's diagnostics region gives the same feedback while writing.
 */
describe("this repo's own docs", () => {
  const repoRoot = findRepoRoot(process.cwd())
  const graph = readGraph(join(repoRoot, 'docs'), loadFlowConfig(join(repoRoot, CONFIG_FILE)))

  it('holds documents to read at all', () => {
    expect(graph.nodes.length).toBeGreaterThan(0)
    expect(graph.nodes.some((node) => node.type === 'spec')).toBe(true)
    expect(graph.nodes.some((node) => node.type === 'record')).toBe(true)
  })

  // spec-00001-AC-40.5 on the real data
  it('parses with no diagnostic at all', () => {
    expect(
      graph.diagnostics.map((found) => `${found.docId} [${found.kind}] line ${found.line}: ${found.text}`),
    ).toEqual([])
  })
})
