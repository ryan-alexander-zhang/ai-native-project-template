/**
 * How many code points of context an anchor carries on either side of the
 * selection (design-00001 §12.2). A design default, open to measurement: too
 * short and the whole key of a common phrase still stands in several places,
 * where layer 2 refuses to guess; too long and any neighbouring edit knocks the
 * whole key out and pushes the reading into layer 2, against
 * spec-00007-FR-2's «a change outside the anchor text does not move it».
 *
 * The unit is a **Unicode code point**, never a UTF-16 code unit: `Array.from`
 * counts them, so a cut can never split a surrogate pair — half an emoji in an
 * anchor is a key no later match can find.
 */
export const CONTEXT_CODE_POINTS = 64

/** A combining mark: the code point class a cut may never be made in front of. */
const COMBINING = /\p{M}/u

/**
 * The selection anchor of one annotation (design-00001 §12.2,
 * spec-00007-FR-2): the selected text, and its context on either side. The
 * three together are the **key** — context is what disambiguates a selection
 * whose text is a common phrase — and the key is cut from the normalised text of
 * the whole file, front matter included, so both editor states share one
 * coordinate system.
 */
export interface SelectionAnchor {
  selected: string
  before: string
  after: string
}

/** Why an anchor lands nowhere: its key is gone, or it stands in several places. */
export type AnchorFailure = 'missing' | 'ambiguous'

/**
 * Where an anchor lands. The offsets are UTF-16 code units — `indexOf`'s and
 * CodeMirror's own unit — which is no contradiction with the code-point count
 * above: 64 is a **count** and these are **indices**, so each takes its own
 * natural unit rather than one of them paying for a conversion layer.
 */
export type AnchorLocation = { start: number; end: number } | { failed: AnchorFailure }

/**
 * The one normalisation, shared by the three places that need it — where an
 * anchor is cut, where it is matched, and where the offsets are handed out
 * (design-00001 §12.2): CRLF and a bare CR become LF, and nothing else happens.
 * Whitespace is not folded and Markdown is not stripped: an anchor anchors
 * source text, and any further normalisation would read a sentence the owner
 * really did rewrite as a hit.
 *
 * It applies on the **reading** side alone — the disk keeps whatever the editor
 * saved.
 */
export function normalizeText(text: string): string {
  return text.replace(/\r\n?/g, '\n')
}

/**
 * The anchor of a selection in the normalised text of a whole file
 * (design-00001 §12.2): the selection, and up to {@link CONTEXT_CODE_POINTS}
 * code points of context each way — less only at the start or the end of the
 * file. Characters, never lines: lines drift with Markdown rewrapping, and the
 * preview mapping hands back character offsets anyway.
 *
 * Called wherever an annotation is born or re-anchored, which is the editor side
 * (spec-00007-FR-1's unsaved buffer is a text only it holds); the server matches
 * against the same normalisation with {@link relocate}.
 */
export function anchorAt(text: string, start: number, end: number): SelectionAnchor {
  return {
    selected: text.slice(start, end),
    before: text.slice(contextStart(text, start), start),
    after: text.slice(end, contextEnd(text, end)),
  }
}

/**
 * Where the context before the selection begins. The cut is pulled further back
 * while the first code point it would keep is a combining mark: a mark whose
 * base is left outside is half a grapheme cluster, and carrying a few code
 * points more costs nothing (design-00001 §12.2).
 */
function contextStart(text: string, start: number): number {
  const points = [...text.slice(0, start)]
  let taken = Math.min(CONTEXT_CODE_POINTS, points.length)
  while (taken < points.length && COMBINING.test(points[points.length - taken]!)) taken += 1
  return start - points.slice(points.length - taken).join('').length
}

/**
 * Where the context after the selection ends — the mirror reading: the cut is
 * pushed further on while the next code point is a combining mark, which would
 * otherwise leave a base inside the anchor and its marks outside it.
 */
function contextEnd(text: string, end: number): number {
  const points = [...text.slice(end)]
  let taken = Math.min(CONTEXT_CODE_POINTS, points.length)
  while (taken < points.length && COMBINING.test(points[taken]!)) taken += 1
  return end + points.slice(0, taken).join('').length
}

/**
 * The marked passage as an agent is shown it (design-00001 §12.4 and §12.5): the
 * context verbatim, with the selection itself fenced in `[[ ]]`. Both paths show
 * it this way — what the agent gets is the source text rather than a retelling
 * (spec-00007-AC-7.1), and the fence is what tells it which sentence was named.
 */
export function markedPassage(anchor: SelectionAnchor): string {
  return `…${anchor.before}[[${anchor.selected}]]${anchor.after}…`
}

/**
 * Where an anchor stands in a text now, in two layers, and only ever on
 * **exactly one** hit (spec-00007-FR-2, design-00001 §12.2):
 *
 * 1. the **whole key** — context, selection, context — scanned over the whole
 *    file. Exactly one is the hit; more than one is an ambiguous hit and fails
 *    **without falling through**, which is the branch spec-00007-AC-2.4's
 *    ambiguous-at-birth selection takes;
 * 2. no hit at all falls back to the **selection alone**, the ordinary shape of
 *    a neighbouring edit having moved the context: none is `missing`, exactly
 *    one is the hit, more than one is `ambiguous`. This layer **scores
 *    nothing** — scoring is guessing which of several places was meant, while
 *    FR-2 says a failed reading is held back rather than one of them silently
 *    taken. Context still disambiguates, in layer 1; by layer 2 the context has
 *    already been edited, so it is spent evidence.
 */
export function relocate(text: string, anchor: SelectionAnchor): AnchorLocation {
  const { before, selected, after } = anchor
  const whole = hits(text, before + selected + after)
  if (whole.length === 1) {
    const start = whole[0]! + before.length
    return { start, end: start + selected.length }
  }
  if (whole.length > 1) return { failed: 'ambiguous' }
  const bare = hits(text, selected)
  if (bare.length === 0) return { failed: 'missing' }
  if (bare.length > 1) return { failed: 'ambiguous' }
  return { start: bare[0]!, end: bare[0]! + selected.length }
}

/**
 * Every position a key stands at, **overlapping ones included** — the scan
 * advances by one, never by the key's own length (design-00001 §12.2). This is
 * what decides the ambiguity count and with it spec-00007-AC-2.3 and AC-2.4: a
 * self-overlapping key (`abab` in `ababab`) counted the non-overlapping way
 * yields one hit, and the reading that should have been held back would be taken
 * silently. Two is as far as it counts — every layer above only asks whether
 * there is none, one, or more than one.
 */
function hits(text: string, key: string): number[] {
  const found: number[] = []
  for (let at = text.indexOf(key); at !== -1; at = text.indexOf(key, at + 1)) {
    found.push(at)
    if (found.length > 1) return found
  }
  return found
}
