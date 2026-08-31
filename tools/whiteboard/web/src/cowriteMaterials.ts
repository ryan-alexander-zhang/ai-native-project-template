import type { CowriteMaterials } from '../../src/cowrite.ts'

/** A line of the reference box the board cannot use, and why (design-00002 §15). */
export interface UnusableLine {
  line: string
  reason: string
}

/** A document of this repo: the type, its five-digit number, and its slug (rule-00001-BR-18). */
const DOC_ID = /^[a-z]+-\d{5}-[a-z0-9-]+$/

/**
 * What the two launch inputs mean, by the discriminators design-00002 §15 writes
 * down and in that order: a leading `/` that is not `//` is a path outside the
 * repo, a `://` anywhere makes a URL, and `<type>-<five digits>-<slug>` is a
 * document of this repo — checked against the board, since an id nothing answers
 * to is a typo, not a material.
 *
 * A line that is none of the three, and one whose id the board does not have,
 * come back as `unusable` and block the launch. Neither is dropped and neither is
 * folded into the pasted text: a mistyped id put there becomes a line of prose
 * nobody reads.
 */
export function readMaterials(
  text: string,
  references: string,
  known: (id: string) => boolean,
): { materials?: CowriteMaterials; unusable: UnusableLine[] } {
  const docIds: string[] = []
  const paths: string[] = []
  const urls: string[] = []
  const unusable: UnusableLine[] = []
  for (const raw of references.split('\n')) {
    const line = raw.trim()
    if (line === '') continue
    if (line.startsWith('/') && !line.startsWith('//')) paths.push(line)
    else if (line.includes('://')) urls.push(line)
    else if (!DOC_ID.test(line)) unusable.push({ line, reason: 'not a document id, an absolute path or a URL' })
    else if (!known(line)) unusable.push({ line, reason: 'no document with this id is on the board' })
    else docIds.push(line)
  }
  const pasted = text.trim()
  const materials: CowriteMaterials = {
    ...(pasted === '' ? {} : { text: pasted }),
    ...(docIds.length === 0 ? {} : { docIds }),
    ...(paths.length === 0 ? {} : { paths }),
    ...(urls.length === 0 ? {} : { urls }),
  }
  // Nothing given is an absent field rather than an empty one: the instruction
  // then carries no materials segment at all (spec-00006-AC-3.3).
  return { materials: Object.keys(materials).length === 0 ? undefined : materials, unusable }
}
