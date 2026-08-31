import { type DocGraph, type DocNode, declaredId, findNode, parseDocId } from './docRepository.ts'
import { WorkflowError } from './workflow.ts'

/**
 * The one type a cowrite session may file a **new** document of
 * (rule-00001-BR-30): reference is the second birth path of BR-26, and the
 * collapse filter judges every new file against this name.
 */
export const REFERENCE_TYPE = 'reference'

/**
 * What the owner handed the session to write from (spec-00006-FR-3): text they
 * pasted, documents in this repo, files outside it, and URLs. Every field is
 * optional and an empty payload is the ordinary case — the instruction then
 * carries no materials segment at all (spec-00006-AC-3.3).
 */
export interface CowriteMaterials {
  text?: string
  docIds?: string[]
  paths?: string[]
  urls?: string[]
}

/** What a cowrite session is told; its contract is spec-00006-FR-1 with design-00001 §11.1. */
export interface CowriteTask {
  /** The document being written, relative to the session's working directory (the docs tree). */
  docPath: string
  /** Its type — what README and what item grammar it is held to. */
  docType: string
  /** Its type folder's README, same relativity: what belongs in a document of this type. */
  readmePath: string
  /** The item grammar of that type, for the types that have one (spec-00001-FR-41). */
  grammar?: string[]
  /** The first free reference number at the moment the session starts (rule-00001-BR-18). */
  referenceStart: number
  /** The materials segment, already rendered (spec-00006-FR-3); empty for no materials. */
  materialLines: string[]
}

/** The note that rides ahead of the user's first printable frame after a hand edit (spec-00006-FR-5). */
export const HAND_EDIT_NOTE = '[用户已手改目标文档，动笔前须重读] '

/**
 * The materials segment (spec-00006-FR-3, design-00001 §11.1): the pasted text
 * verbatim as a block, in-repo ids rendered with the path they resolve to, and
 * the outside-the-repo paths and URLs listed with the note that reading them is
 * the CLI's own permission business — the board neither pre-authorises nor
 * answers those prompts (spec-00006-FR-7).
 *
 * An id that names no document in this repo is refused rather than passed on: a
 * path the agent cannot read is a material it would go looking for and never
 * find. Nothing given at all yields no segment.
 */
export function materialLines(materials: CowriteMaterials | undefined, graph: DocGraph): string[] {
  const text = materials?.text?.trim() === '' ? undefined : materials?.text
  const docIds = materials?.docIds ?? []
  const paths = materials?.paths ?? []
  const urls = materials?.urls ?? []
  if (text === undefined && docIds.length === 0 && paths.length === 0 && urls.length === 0) return []
  const outside = [...paths, ...urls]
  return [
    'The materials the owner gave you for this document:',
    ...(text === undefined ? [] : ['The text they pasted, whole:', text]),
    ...(docIds.length === 0
      ? []
      : [`Documents in this repo: ${docIds.map((id) => materialPath(graph, id)).join(', ')}`]),
    ...(paths.length === 0 ? [] : [`Files outside this repo: ${paths.join(', ')}`]),
    ...(urls.length === 0 ? [] : [`URLs: ${urls.join(', ')}`]),
    ...(outside.length === 0
      ? []
      : [
          'Reading anything outside this repository goes through your own permission mechanism, and whether',
          '  it asks the owner is that mechanism’s own policy: the board neither pre-authorises the read nor',
          '  answers for them, and a refusal or an unreachable material ends nothing — carry on with the rest.',
        ]),
  ]
}

function materialPath(graph: DocGraph, docId: string): string {
  const node = findNode(graph, docId)
  if (!node) {
    throw new WorkflowError(`${docId} is not a document in this repo, so it cannot be a cowrite material`)
  }
  return `${docId} at ${node.path}`
}

/**
 * The first input written to a cowrite session's pty (design-00001 §11.1): what
 * document this is, what it is held to, what may be written — and what the
 * materials are. The write-scope declaration is the first of the two constraints
 * on the session; the collapse filter of FR-6 is the second, and it is named here
 * so an agent that reads this knows what happens to a stray write rather than
 * discovering it at the end (rule-00001-BR-30).
 */
export function cowriteInstruction(task: CowriteTask): string {
  const { docPath, docType, readmePath, grammar, referenceStart, materialLines: materials } = task
  return [
    'This is a cowrite session: the owner of one document and you write its body together, turn by turn.',
    `The document: ${docPath} (relative to your working directory, the docs tree) — one ${docType} document.`,
    'Read it first.',
    `What belongs in it: ${readmePath}, the README of its own folder.`,
    ...(grammar === undefined
      ? []
      : [`Its body must follow the item grammar of ${readmePath} (「机器可读形态」):`, ...grammar]),
    'What you may write, and nothing else:',
    `  the body of ${docPath} — never its front matter id or status line;`,
    `  new ${REFERENCE_TYPE} documents, as set out below.`,
    'Every other change under the docs tree is filtered out and restored when this session ends',
    `  (rule-00001-BR-30), so writing one is work thrown away. Leave ${docPath} in the status it is in —`,
    '  a human moves it from the board.',
    `To land a new ${REFERENCE_TYPE} document:`,
    `  follow ${REFERENCE_TYPE}/TEMPLATE.md for its front matter and ${REFERENCE_TYPE}/README.md for what`,
    '    belongs in it;',
    `  give it the id ${REFERENCE_TYPE}-<nnnnn>-<slug>: the numbers of this session start at`,
    `    ${numberOf(referenceStart)} and run on one by one from there, and you choose each slug;`,
    '  set status: draft;',
    `  file it at ${REFERENCE_TYPE}/<id>.md — the file name is the id itself.`,
    ...materials,
    'Whatever a material carries that supports a conclusion goes into the body of the document, or into a',
    `  new ${REFERENCE_TYPE} document: a material nobody can read again is no evidence (rule-00001-BR-28).`,
    'Change nothing outside the docs tree.',
  ].join('\n')
}

/** The five-digit form a new id carries (rule-00001-BR-18). */
function numberOf(count: number): string {
  return `${REFERENCE_TYPE}-${String(count).padStart(5, '0')}-`
}

const FRONT_MATTER_FENCE = '---'
/**
 * The three key lines, read the way `workflow.ts` reads them: the colon may carry
 * no space after it. A narrower pattern would miss the `status:draft` an agent
 * wrote and **insert a second** `status` line beside it, which is a front matter
 * with two of the same key — the very anomaly the guard exists to prevent
 * (spec-00006-AC-6.4).
 */
const ID_LINE = /^id\s*:/
const TYPE_LINE = /^type\s*:/
const STATUS_LINE = /^status\s*:/

/**
 * The front matter guard of design-00001 §11.4: the target document's `id` and
 * `status` lines put back to what they were when the session started, in place,
 * with the body left exactly as the session wrote it (spec-00006-AC-6.4,
 * rule-00001-AC-30.5). A line the session removed is put back; a line it changed
 * is changed back, and nothing else in the file is touched.
 *
 * A file whose front matter block is gone altogether is no place to put a line
 * back into: it is reported instead, and the product validation of
 * spec-00001-FR-17 is what the reader hears it from.
 */
export function guardFrontMatter(
  content: string,
  preId: string,
  preStatus: string,
): { content: string; problem?: string } {
  const lines = content.split('\n')
  const end = frontMatterEnd(lines)
  if (end === -1) {
    return { content, problem: 'its front matter block is gone, so the id and status lines could not be put back' }
  }
  return {
    content: fill(lines, end, [
      [ID_LINE, `id: ${preId}`],
      [STATUS_LINE, `status: ${preStatus}`],
    ]).join('\n'),
  }
}

/**
 * A type's `TEMPLATE.md` with the three front matter lines a new document has to
 * carry filled in (design-00001 §11.2): the same prefill `GET /api/create` hands
 * the editor, done here because the create form of cowrite files the document
 * itself rather than routing it through a save (spec-00006-FR-2). A template with
 * no front matter block gets the minimal one — a document has to declare these
 * three or it is anomalous the moment it lands (spec-00001-FR-2).
 */
export function prefilledTemplate(template: string, id: string, type: string): string {
  const lines = template.split('\n')
  const end = frontMatterEnd(lines)
  if (end === -1) return `---\nid: ${id}\ntype: ${type}\nstatus: draft\n---\n\n${template}`
  return fill(lines, end, [
    [ID_LINE, `id: ${id}`],
    [TYPE_LINE, `type: ${type}`],
    [STATUS_LINE, 'status: draft'],
  ]).join('\n')
}

/**
 * Where the front matter block closes, or -1 when the file opens with none. The
 * fences are compared **normalised** — a leading BOM and a trailing CR off — so a
 * CRLF file and a BOM-led one are not read as bodies with no front matter at all,
 * which would have the guard report a block that is right there (design-00001
 * §11.3 步骤 1).
 */
function frontMatterEnd(lines: string[]): number {
  if (fence(lines[0] ?? '') !== FRONT_MATTER_FENCE) return -1
  return lines.findIndex((line, index) => index > 0 && fence(line) === FRONT_MATTER_FENCE)
}

function fence(line: string): string {
  return line.replace(/^\uFEFF/, '').trimEnd()
}

/**
 * Front matter lines set to the values given, in place: a line that is there is
 * replaced, one that is missing is inserted at the head of the block. Everything
 * else in the file — the body included — is left exactly as it was.
 */
function fill(lines: string[], end: number, entries: ReadonlyArray<readonly [RegExp, string]>): string[] {
  let filled = lines
  // The closing fence moves down with every insertion, and the search below is
  // bounded by it: read once, the second missing line would be looked for in a
  // block one line shorter than the file now has.
  let fence = end
  for (const [pattern, line] of entries) {
    const at = filled.findIndex((candidate, i) => i > 0 && i < fence && pattern.test(candidate))
    if (at === -1) {
      filled = [...filled.slice(0, 1), line, ...filled.slice(1)]
      fence += 1
    } else {
      filled = filled.with(at, line)
    }
  }
  return filled
}

/** One file the session left under `reference/`, and the document a fresh read made of it. */
export interface ReferenceCandidate {
  /** Repo-relative, which is what is staged or deleted. */
  path: string
  /** The node the fresh read of the tree made of it; absent when it made none. */
  node?: DocNode
}

export interface ReferenceVerdict {
  /** The paths that may be staged — the well-formed set (rule-00001-BR-30). */
  wellFormed: string[]
  /** The paths to delete, each with why (spec-00006-FR-6). */
  rejected: Array<{ path: string; reason: string }>
}

/**
 * Whether the references a cowrite session created are 合式, judged as the set
 * they are (spec-00006-FR-6, design-00001 §11.3): the per-file readings first —
 * front matter that parses, `type: reference`, `status: draft`, the canonical
 * path `reference/<id>.md`, an id of legal form that no other document declares,
 * and a **number** no other reference has taken — and then the numbering of what
 * survives them, which is a property of the set and of nothing else. `highest` is
 * the greatest reference number that exists at the moment of the collapse, the
 * registry's reserved numbers folded in and these candidates left out; the
 * survivors have to run on from it one by one, or reading BR-18 per file would
 * make the second document of a session illegal by construction.
 *
 * The order is the whole point of the two-session case (spec-00006-AC-6.3): two
 * cowrites admitted at the same moment are handed the same starting number, and
 * the one that collapses second finds the first one's documents already landed.
 * A taken number is a **per-file** reading, so only the colliding candidate dies
 * and the rest of the session's run is judged against the fresh maximum — which
 * now counts what landed. Whole-set rejection is left for the survivors that are
 * non-contiguous among themselves: no one member of such a set is the one that
 * took the wrong number.
 */
export function judgeReferences(
  candidates: readonly ReferenceCandidate[],
  taken: ReadonlySet<string>,
  highest: number,
): ReferenceVerdict {
  const takenNumbers = new Set(
    [...taken]
      .map((id) => parseDocId(id))
      .flatMap((parsed) => (parsed?.type === REFERENCE_TYPE ? [parsed.number] : [])),
  )
  const rejected: Array<{ path: string; reason: string }> = []
  const sound: Array<{ path: string; number: number }> = []
  for (const candidate of candidates) {
    const reason = malformed(candidate, taken, takenNumbers)
    if (reason !== undefined) rejected.push({ path: candidate.path, reason })
    else sound.push({ path: candidate.path, number: parseDocId(declaredId(candidate.node!))!.number })
  }
  const run = sound.map(({ number }) => number).sort((a, b) => a - b)
  const contiguous = run.every((number, index) => number === highest + 1 + index)
  if (contiguous) return { wellFormed: sound.map(({ path }) => path), rejected }
  return {
    wellFormed: [],
    rejected: [
      ...rejected,
      ...sound.map(({ path }) => ({
        path,
        reason: `its number is not part of the run from ${highest + 1} this session's references had to take`,
      })),
    ],
  }
}

/** Why this file is no well-formed reference, or nothing when it is one. */
function malformed(
  candidate: ReferenceCandidate,
  taken: ReadonlySet<string>,
  takenNumbers: ReadonlySet<number>,
): string | undefined {
  const { node } = candidate
  if (!node) return 'it is no document the board can read'
  const id = declaredId(node)
  if (node.type !== REFERENCE_TYPE) return `its type is ${JSON.stringify(node.type)}, not ${REFERENCE_TYPE}`
  if (node.status !== 'draft') return `its status is ${JSON.stringify(node.status)}, not draft`
  const parsed = parseDocId(id)
  if (!parsed) return `its id ${JSON.stringify(id)} is not <type>-<nnnnn>-<slug>`
  if (node.path !== `${REFERENCE_TYPE}/${id}.md`) {
    return `it is at ${node.path} rather than at its canonical ${REFERENCE_TYPE}/${id}.md`
  }
  if (taken.has(id)) return `${id} is already the id of another document`
  // Another slug on the same number is a different id and no collision, so this
  // reading is its own: rule-00001-BR-18 hands each document a number nobody
  // else holds, and the session that collapsed first already took this one.
  if (takenNumbers.has(parsed.number)) {
    return `its number is already taken by another ${REFERENCE_TYPE} document`
  }
  return undefined
}
