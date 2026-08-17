/**
 * Requirement items, their acceptance criteria, and the record rows that verify
 * them — spec-00001-FR-31 … FR-33, whose coverage verdict is derived here and
 * never in the browser (design-00001 §2).
 *
 * The parsing is deliberately literal: the id syntax and the two declaration
 * shapes are what the folder READMEs already define, so a body that drifts from
 * them stops parsing instead of guessing (decision-00004 §4).
 */

/** `spec-00001-FR-3`, `rule-00001-BR-2`, `spec-00001-AC-1.10`. */
const DECLARED_ID = /^([a-z]+-\d{5})-(FR|BR|AC)-(\d+)(?:\.(\d+))?$/
const DOC_PREFIX = /^([a-z]+-\d{5})-/
/** A list item: `- **spec-00001-FR-1** (Event) …`. */
const LIST_DECLARATION = /^-[ \t]+\*\*([^*]+)\*\*[ \t]*(.*)$/
/** A decision-table row: `| **rule-00001-BR-2** | living doc | … |`. */
const ROW_DECLARATION = /^\|[ \t]*\*\*([^*]+)\*\*[ \t]*\|(.*)$/
/** An indented line continues the declaration above it. */
const CONTINUATION = /^[ \t]+\S/
/** The `(spec-00001-FR-1)` an acceptance criterion carries to say what it verifies. */
const ATTRIBUTION = /^\(([^)]+)\)[ \t]*/
const TEST_COLUMN = /test|测试/i
const RESULT_COLUMN = /result|结果/i
const EVIDENCE_COLUMN = /evidence|证据/i
const PASS = 'pass'

/** Only spec and rule declare requirement items; other types are out of scope (spec-00001 §6). */
const ITEM_TYPES = new Set(['spec', 'rule'])

export type Coverage = 'verified' | 'failing' | 'uncovered'

/** A row of a record's acceptance checklist: what it verified, with which test, and how it went. */
export interface AcceptanceRow {
  recordId: string
  targetId: string
  test: string
  result: string
  /** What the row offers as proof; absent when the checklist has no Evidence column (design-00001 §7). */
  evidence?: string
}

export interface Criterion {
  id: string
  text: string
  rows: AcceptanceRow[]
}

export interface RequirementItem {
  id: string
  text: string
  criteria: Criterion[]
  /** Rows naming the item id itself — `docs/record/README.md` allows that shape. */
  rows: AcceptanceRow[]
  coverage: Coverage
}

/** A row or criterion with nowhere to belong (spec-00001-FR-33). */
export interface UnattributedEntry {
  declaredId: string
  /** The record the row came from; absent when the entry is a criterion of the document itself. */
  recordId?: string
  /** The item a criterion claimed to verify, when no such item exists. */
  attributedTo?: string
}

export interface ItemsView {
  items: RequirementItem[]
  unattributed: UnattributedEntry[]
}

export interface DocBody {
  id: string
  body: string
}

type ItemDraft = Omit<RequirementItem, 'coverage'>

interface Declaration {
  id: string
  text: string
  /** `[prefix, kind, number, sub]` when the id is a requirement id. */
  parts: RegExpExecArray | null
}

export function declaresItems(type: string | undefined): boolean {
  return type !== undefined && ITEM_TYPES.has(type)
}

/**
 * Every requirement item and acceptance criterion id the document declares —
 * the index that lets a relation field point at an item and still resolve to
 * the document holding it (spec-00001-FR-2 as amended).
 */
export function declaredIds(doc: DocBody): string[] {
  return ownDeclarations(doc).map((declaration) => declaration.id)
}

/** The items of one spec or rule, with their criteria, verifying rows, and coverage. */
export function requirementView(doc: DocBody, records: DocBody[]): ItemsView {
  const prefix = DOC_PREFIX.exec(doc.id)?.[1]
  if (prefix === undefined) return { items: [], unattributed: [] }

  const declarations = ownDeclarations(doc)
  const items = declarations.filter(isItem).sort(byNumber).map(toDraft)
  const unattributed: UnattributedEntry[] = []
  attachCriteria(items, declarations.filter(isCriterion).sort(byNumber), unattributed)
  attachRows(items, verifyingRows(records, prefix), unattributed)

  return { items: items.map((item) => ({ ...item, coverage: coverageOf(item) })), unattributed }
}

/**
 * The three states, first hit wins (spec-00001-FR-32, decision-00004 §2/§5):
 * a row that did not pass outranks everything; no criteria at all, or a
 * criterion nobody referenced, is a gap — an item-level `pass` does not stand
 * in for the per-criterion references.
 */
function coverageOf(item: ItemDraft): Coverage {
  const rows = [...item.rows, ...item.criteria.flatMap((criterion) => criterion.rows)]
  if (rows.some((row) => row.result.toLowerCase() !== PASS)) return 'failing'
  if (item.criteria.length === 0) return 'uncovered'
  return item.criteria.some((criterion) => criterion.rows.length === 0) ? 'uncovered' : 'verified'
}

function attachCriteria(items: ItemDraft[], criteria: Declaration[], unattributed: UnattributedEntry[]): void {
  for (const criterion of criteria) {
    const attributedTo = ATTRIBUTION.exec(criterion.text)?.[1]
    const owner = items.find((item) => item.id === attributedTo)
    if (!owner) {
      unattributed.push({ declaredId: criterion.id, attributedTo })
      continue
    }
    owner.criteria.push({ id: criterion.id, text: criterion.text.replace(ATTRIBUTION, ''), rows: [] })
  }
}

function attachRows(items: ItemDraft[], rows: AcceptanceRow[], unattributed: UnattributedEntry[]): void {
  const criteria = items.flatMap((item) => item.criteria)
  for (const row of rows) {
    const target =
      items.find((item) => item.id === row.targetId) ?? criteria.find((criterion) => criterion.id === row.targetId)
    if (target) target.rows.push(row)
    else unattributed.push({ recordId: row.recordId, declaredId: row.targetId })
  }
}

/** The acceptance rows, across every record, that name something belonging to `prefix`. */
function verifyingRows(records: DocBody[], prefix: string): AcceptanceRow[] {
  return records
    .flatMap((record) => acceptanceRows(record))
    .filter((row) => DECLARED_ID.exec(row.targetId)?.[1] === prefix)
}

/**
 * The rows of a record's acceptance checklists: a table whose header names a
 * test and a result column, and whose first cell is the id being verified. A
 * table of ids and prose (the amendment tables) is not one; an Evidence column
 * neither adds nor removes anything (spec-00001-FR-32).
 */
export function acceptanceRows(record: DocBody): AcceptanceRow[] {
  const lines = record.body.split('\n')
  const rows: AcceptanceRow[] = []
  let columns: VerificationColumns | undefined

  lines.forEach((line, index) => {
    if (!line.trimStart().startsWith('|')) {
      columns = undefined
      return
    }
    if (isDivider(line)) return
    const cells = tableCells(line)
    if (isDivider(lines[index + 1] ?? '')) {
      columns = verificationColumns(cells)
      return
    }
    // A split always yields a first cell, and only the first cell says what the row verifies.
    const targetId = cells[0]!
    if (!columns || !DECLARED_ID.test(targetId)) return
    const evidence = columns.evidence === undefined ? '' : (cells[columns.evidence] ?? '')
    rows.push({
      recordId: record.id,
      targetId,
      test: cells[columns.test] ?? '',
      result: cells[columns.result] ?? '',
      // Left out rather than left empty: a checklist with no Evidence column has
      // no such field to show (design-00001 §7, spec-00001-AC-37.8).
      ...(evidence === '' ? {} : { evidence }),
    })
  })
  return rows
}

interface VerificationColumns {
  test: number
  result: number
  /** Optional by contract: an Evidence column neither adds nor removes a row (spec-00001-FR-32). */
  evidence?: number
}

/** A checklist header carries a test and a result column, neither of them the id column. */
function verificationColumns(header: string[]): VerificationColumns | undefined {
  const test = header.findIndex((cell) => TEST_COLUMN.test(cell))
  const result = header.findIndex((cell) => RESULT_COLUMN.test(cell))
  const evidence = header.findIndex((cell) => EVIDENCE_COLUMN.test(cell))
  return test > 0 && result > 0 ? { test, result, ...(evidence > 0 ? { evidence } : {}) } : undefined
}

function isDivider(line: string): boolean {
  return /^\|[\s:|-]+\|$/.test(line.trim()) && line.includes('-')
}

/** The cells of a GFM table row, stripped of the outer pipes and of markdown decoration. */
function tableCells(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(clean)
}

function clean(text: string): string {
  return text.replace(/[*`]/g, '').trim()
}

/** Declarations whose id belongs to this document — anything else is somebody's quotation. */
function ownDeclarations(doc: DocBody): Declaration[] {
  const prefix = DOC_PREFIX.exec(doc.id)?.[1]
  if (prefix === undefined) return []
  return declarations(doc.body).filter((declaration) => declaration.parts?.[1] === prefix)
}

/** Both declaration shapes, with an indented run of lines folded into the one above. */
function declarations(body: string): Declaration[] {
  const found: { id: string; text: string }[] = []
  let open: { id: string; text: string } | undefined

  for (const line of body.split('\n')) {
    const list = LIST_DECLARATION.exec(line)
    const row = ROW_DECLARATION.exec(line)
    if (list) {
      open = { id: list[1]!.trim(), text: list[2]! }
      found.push(open)
    } else if (row) {
      open = undefined
      found.push({ id: row[1]!.trim(), text: tableCells(`|${row[2]!}`).join(' | ') })
    } else if (open && CONTINUATION.test(line)) {
      open.text += ` ${line.trim()}`
    } else {
      open = undefined
    }
  }
  return found.map(({ id, text }) => ({ id, text: text.replace(/\s+/g, ' ').trim(), parts: DECLARED_ID.exec(id) }))
}

function isItem(declaration: Declaration): boolean {
  return declaration.parts?.[2] !== 'AC'
}

function isCriterion(declaration: Declaration): boolean {
  return declaration.parts?.[2] === 'AC'
}

/** Ascending by requirement number, then by the criterion's own number (spec-00001-FR-31). */
function byNumber(a: Declaration, b: Declaration): number {
  return Number(a.parts![3]) - Number(b.parts![3]) || Number(a.parts![4] ?? 0) - Number(b.parts![4] ?? 0)
}

function toDraft(declaration: Declaration): ItemDraft {
  return { id: declaration.id, text: declaration.text, criteria: [], rows: [] }
}
