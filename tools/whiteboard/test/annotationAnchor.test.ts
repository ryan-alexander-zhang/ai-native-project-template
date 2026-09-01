import { describe, expect, it } from 'vitest'
import {
  CONTEXT_CODE_POINTS,
  type SelectionAnchor,
  anchorAt,
  markedPassage,
  normalizeText,
  relocate,
} from '../src/annotationAnchor.ts'

/** The anchor of the first occurrence of `selected` in `text`, the way the editor cuts one. */
function anchorOf(text: string, selected: string): SelectionAnchor {
  const at = text.indexOf(selected)
  return anchorAt(text, at, at + selected.length)
}

describe('normalizeText', () => {
  /**
   * The one normalisation of design-00001 §12.2, and only it: line endings. On a
   * repo with no CRLF file this is the identity today — it is written down so
   * that the day one arrives, the two sides do not each read their own text.
   */
  it('turns CRLF and a bare CR into LF and leaves everything else alone', () => {
    expect(normalizeText('a\r\nb\rc\nd')).toBe('a\nb\nc\nd')
    expect(normalizeText('  two  spaces\n**bold**')).toBe('  two  spaces\n**bold**')
  })
})

describe('anchorAt', () => {
  // The three segments of design-00001 §12.2: the selection, and its context.
  it('carries the selection and up to 64 code points of context each way', () => {
    const text = `${'a'.repeat(100)}the sentence${'b'.repeat(100)}`

    const anchor = anchorOf(text, 'the sentence')

    expect(anchor.selected).toBe('the sentence')
    expect(anchor.before).toBe('a'.repeat(CONTEXT_CODE_POINTS))
    expect(anchor.after).toBe('b'.repeat(CONTEXT_CODE_POINTS))
  })

  it('takes what there is at the start and the end of the file', () => {
    const anchor = anchorOf('ab the sentence cd', 'the sentence')

    expect(anchor.before).toBe('ab ')
    expect(anchor.after).toBe(' cd')
  })

  /**
   * The unit is a code point, so a cut can never fall inside a surrogate pair:
   * half an emoji in the key is a key no later match can find (design-00001
   * §12.2).
   */
  it('counts code points, so 64 emoji of context are 64 and none of them is halved', () => {
    const text = `${'🍅'.repeat(80)}the sentence${'🥕'.repeat(80)}`

    const anchor = anchorOf(text, 'the sentence')

    expect([...anchor.before]).toHaveLength(CONTEXT_CODE_POINTS)
    expect([...anchor.after]).toHaveLength(CONTEXT_CODE_POINTS)
    expect(anchor.before).toBe('🍅'.repeat(CONTEXT_CODE_POINTS))
    expect(anchor.after).toBe('🥕'.repeat(CONTEXT_CODE_POINTS))
    // And the key it forms still stands where it was cut from.
    const at = text.indexOf('the sentence')
    expect(relocate(text, anchor)).toEqual({ start: at, end: at + 'the sentence'.length })
  })

  /**
   * Nor inside a combining sequence: the cut is pulled back, or pushed on, until
   * no combining mark is left without its base (design-00001 §12.2). The acute
   * accent below lands exactly on each boundary — the 64th code point back is a
   * mark whose base is one further out, and the code point just past the 64th on
   * is a mark whose base is the last one taken.
   */
  it('swallows a few code points more rather than leaving half a grapheme cluster', () => {
    const text = `${'x'.repeat(35)}é${'y'.repeat(63)}the sentence${'z'.repeat(CONTEXT_CODE_POINTS)}́ and on`

    const anchor = anchorOf(text, 'the sentence')

    expect([...anchor.before]).toHaveLength(CONTEXT_CODE_POINTS + 1)
    expect(anchor.before.startsWith('é')).toBe(true)
    expect([...anchor.after]).toHaveLength(CONTEXT_CODE_POINTS + 1)
    expect(anchor.after.endsWith('ź')).toBe(true)
  })
})

describe('markedPassage', () => {
  // spec-00007-AC-7.1's «the source text, not a retelling»: context verbatim, the
  // selection fenced so the agent can tell which sentence was named.
  it('shows the context whole with the selection fenced in double brackets', () => {
    expect(markedPassage({ before: 'left ', selected: 'this', after: ' right' })).toBe('…left [[this]] right…')
  })
})

/**
 * The two-layer reading of spec-00007-FR-2, at every branch it has
 * (design-00001 §12.2): the whole key, the selection alone, and the three
 * verdicts each of them can reach.
 */
describe('relocate', () => {
  const DOC = '---\nid: spec-00001-x\nstatus: draft\n---\n\n# Title\n\nThe gate is cheap to check.\n\nAnd on.\n'
  const anchor = anchorOf(DOC, 'The gate is cheap to check.')

  /**
   * spec-00007-AC-2.1 — the anchor is content, not a line number: two paragraphs
   * inserted ahead of the sentence move nothing about the reading.
   */
  // spec-00007-AC-2.1
  it('finds the sentence again after two paragraphs are inserted before it', () => {
    const edited = DOC.replace('# Title\n', '# Title\n\nOne new paragraph.\n\nAnd a second one.\n')

    const found = relocate(edited, anchor)

    expect(found).toEqual({
      start: edited.indexOf('The gate is cheap to check.'),
      end: edited.indexOf('The gate is cheap to check.') + 'The gate is cheap to check.'.length,
    })
    expect(edited.slice(...offsets(found))).toBe('The gate is cheap to check.')
  })

  /**
   * The ordinary layer-2 case: the context around the sentence was edited, so the
   * whole key is gone while the sentence itself is still there, once.
   */
  it('falls back to the selection alone when only the context moved', () => {
    const edited = DOC.replace('# Title', '# A better title altogether')

    expect(edited.slice(...offsets(relocate(edited, anchor)))).toBe('The gate is cheap to check.')
  })

  // spec-00007-AC-2.2 — the anchor text itself is gone
  it('fails as missing when the sentence has been deleted', () => {
    const edited = DOC.replace('The gate is cheap to check.\n', '')

    expect(relocate(edited, anchor)).toEqual({ failed: 'missing' })
  })

  // spec-00007-AC-5.2 — the anchor text was rewritten, which is the same reading
  it('fails as missing when the sentence has been rewritten', () => {
    const edited = DOC.replace('The gate is cheap to check.', 'The gate is expensive to check.')

    expect(relocate(edited, anchor)).toEqual({ failed: 'missing' })
  })

  // spec-00007-AC-2.3 — a later edit put the key in two places: held back, never
  // one of them silently taken
  it('fails as ambiguous when a later edit gave the whole key a second place', () => {
    const bare: SelectionAnchor = { before: '', selected: 'the gate', after: '' }

    expect(relocate('the gate here and the gate there', bare)).toEqual({ failed: 'ambiguous' })
  })

  /**
   * The same verdict from layer 2, which is the shape spec-00007-AC-2.3 describes:
   * the context was edited so the whole key is nowhere, and the sentence itself has
   * meanwhile been copied. Layer 2 only accepts a single hit — it does not score
   * the candidates and pick one, because a guess that misses points the annotation
   * at the wrong passage.
   */
  // spec-00007-AC-2.3
  it('fails as ambiguous when the context is gone and the sentence stands twice', () => {
    const edited = DOC.replace('# Title', '# Retitled').replace('And on.\n', `${'The gate is cheap to check.'}\n`)

    expect(relocate(edited, anchor)).toEqual({ failed: 'ambiguous' })
  })

  /**
   * spec-00007-AC-2.4 — ambiguous the moment it was made: the selection sits in
   * one of two passages that repeat word for word, so its whole key stands twice.
   * Layer 1 does **not** fall through to layer 2 on this: the two failures are
   * the same failure.
   */
  // spec-00007-AC-2.4
  it('fails as ambiguous when the whole key stood twice from the start', () => {
    // Repeated word for word further than the context reaches on either side,
    // which is what makes even the whole key ambiguous the moment it is cut.
    const passage = `${'a'.repeat(80)} two three ${'b'.repeat(80)}`
    const twice = `${passage}\n\n${passage}\n`
    const born = anchorOf(twice, 'two three')

    expect(relocate(twice, born)).toEqual({ failed: 'ambiguous' })
  })

  /**
   * The scan enumerates **overlapping** positions. Counted the non-overlapping
   * way, a self-overlapping key stands once and the reading that should have been
   * held back would be taken silently — the very thing FR-2 forbids.
   */
  it('counts a self-overlapping key as the two hits it is', () => {
    expect(relocate('ababab', { before: '', selected: 'abab', after: '' })).toEqual({ failed: 'ambiguous' })
  })

  /**
   * The coordinate system is the whole file, front matter included: an anchor cut
   * across the front matter fence lands on an offset counted from byte nought of
   * the file (design-00001 §12.2).
   */
  it('reads offsets over the whole file, front matter included', () => {
    const found = relocate(DOC, anchorOf(DOC, 'status: draft'))

    expect(DOC.slice(...offsets(found))).toBe('status: draft')
    expect((found as { start: number }).start).toBeLessThan(DOC.indexOf('# Title'))
  })

  /**
   * The anchor is cut from normalised text and matched against normalised text,
   * so a document that arrives with CRLF endings reads the same as one with LF —
   * the reading that keeps the two sides on one coordinate system.
   */
  it('matches an anchor cut from LF text against the same document in CRLF', () => {
    const crlf = DOC.replace(/\n/g, '\r\n')
    const multiline = anchorOf(DOC, 'The gate is cheap to check.\n\nAnd on.')

    expect(relocate(crlf, multiline)).toEqual({ failed: 'missing' })
    expect(normalizeText(crlf).slice(...offsets(relocate(normalizeText(crlf), multiline)))).toBe(
      'The gate is cheap to check.\n\nAnd on.',
    )
  })
})

function offsets(found: ReturnType<typeof relocate>): [number, number] {
  if ('failed' in found) throw new Error(`expected a hit, got ${found.failed}`)
  return [found.start, found.end]
}
