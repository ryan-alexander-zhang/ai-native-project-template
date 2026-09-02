import { normalizeText } from '../../src/annotationAnchor.ts'
import { frontMatterPrefix } from './frontMatter.ts'

/**
 * One half-open interval of the document's source text. Which text depends on
 * which side is holding it, and there are exactly two (design-00002 §16.3):
 *
 * - **file coordinates** — the normalised text of the whole file, front matter
 *   included. Everything the server hands over and everything an anchor is cut
 *   from is in these, so the editor and the preview share one system;
 * - **body coordinates** — the same text with the front matter prefix taken
 *   off, which is what the preview renders and what its rehype plugin reports.
 *
 * The two conversions below are the only place either crossing happens.
 */
export interface SourceRange {
  start: number
  end: number
}

/**
 * The buffer as every offset in this module reads it: the server's own
 * normalisation, applied to the reading side and nothing else
 * (design-00001 §12.2 — CRLF and a bare CR become LF). Imported rather than
 * mirrored, so the text an anchor is cut from here and the text it is matched
 * against there cannot come to mean two things.
 */
export function normalized(buffer: string): string {
  return normalizeText(buffer)
}

/**
 * A CodeMirror offset — which counts the buffer as it is — read as an offset of
 * the normalised text. Only a `\r\n` shrinks, so the difference is the number of
 * those before it; on a file with no CRLF this is the identity, and it is
 * written down anyway so that the two sides never come to disagree by accident
 * (design-00002 §16.3).
 */
export function normalizedOffset(buffer: string, at: number): number {
  return at - (buffer.slice(0, at).match(/\r\n/g)?.length ?? 0)
}

/** And back: where an offset of the normalised text stands in the buffer itself. */
export function bufferOffset(buffer: string, at: number): number {
  let index = 0
  for (let seen = 0; index < buffer.length && seen < at; seen += 1) {
    if (buffer.startsWith('\r\n', index)) index += 1
    index += 1
  }
  return index
}

/** The front matter prefix of a normalised buffer, from its one computation. */
export function bodyPrefix(text: string): number {
  return frontMatterPrefix(text)
}

/** A body offset lifted into file coordinates: the addition of design-00002 §16.3. */
export function toFileOffset(bodyOffset: number, prefix: number): number {
  return bodyOffset + prefix
}

/**
 * A file interval lowered into the preview's body coordinates: the subtraction
 * of design-00002 §16.3. One that lands inside the front matter has no place in
 * the preview at all — nothing is drawn for it and its locate entry is disabled
 * — which is what `undefined` says.
 */
export function toBodyRange(range: SourceRange, prefix: number): SourceRange | undefined {
  if (range.start < prefix) return undefined
  return { start: range.start - prefix, end: range.end - prefix }
}
