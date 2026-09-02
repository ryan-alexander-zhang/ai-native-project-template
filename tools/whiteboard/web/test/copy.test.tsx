import { readFileSync, readdirSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/** The web sources this guard reads; the vendored shadcn/ui tree is not ours to word. */
const ROOT = fileURLToPath(new URL('../src', import.meta.url))
const SKIP = 'components/ui'

/** A doc id or a section sign — how the repo cites its own docs. */
const CITATION = /(design|spec|decision|rule|plan|issue|record)-\d{5}\b|§\d/

/**
 * The one citation-shaped string that is not a citation: the materials box
 * shows what a user types, and a doc id is exactly that.
 */
const ALLOWED: Record<string, string> = {
  'MaterialsInput.tsx': 'spec-00001-whiteboard',
}

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const path = join(dir, e.name)
    if (e.isDirectory()) return relative(ROOT, path) === SKIP ? [] : sources(path)
    return /\.tsx?$/.test(e.name) ? [path] : []
  })
}

/**
 * Comments out, code left. Only block comments and `//` comments that open a
 * line (after whitespace, or right after a `{`) are stripped, so a `//` inside a
 * string or template literal — a URL, say — survives. That is deliberate: an
 * end-of-line comment after code is left in, which can only make the guard
 * stricter, never blinder.
 */
function code(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|\{)[ \t]*\/\/.*$/gm, '$1')
}

// issue-00025
describe('user-facing copy', () => {
  it('cites no doc id outside a comment', () => {
    const leaks = sources(ROOT).flatMap((path) => {
      const allowed = ALLOWED[path.split('/').pop() as string]
      const lines = code(readFileSync(path, 'utf8')).split('\n')
      return lines.flatMap((line, i) => {
        const rest = allowed === undefined ? line : line.split(allowed).join('')
        return CITATION.test(rest) ? [`${relative(ROOT, path)}:${i + 1}: ${line.trim()}`] : []
      })
    })

    expect(leaks).toEqual([])
  })
})
