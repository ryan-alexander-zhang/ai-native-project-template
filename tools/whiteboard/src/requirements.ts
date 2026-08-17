/**
 * Requirement items, their acceptance criteria, and the record rows that verify
 * them — spec-00001-FR-31 … FR-33, whose coverage verdict is derived here and
 * never in the browser (design-00001 §2) — together with the parse diagnostics
 * of spec-00001-FR-40.
 *
 * The grammar is the one the folder READMEs publish ("机器可读形态"): two
 * declaration shapes, an attribution on every criterion, one id per checklist
 * row. The reader is a remark AST rather than a line scanner (decision-00005
 * §2 第 4 条), so what drifts from the grammar is reported with its line
 * instead of being silently skipped.
 */

import type { ListItem, Nodes, Root, TableCell, TableRow } from 'mdast'
import remarkGfm from 'remark-gfm'
import remarkParse from 'remark-parse'
import { unified } from 'unified'

/** `spec-00001-FR-3`, `rule-00001-BR-2`, `spec-00001-AC-1.10`. */
const DECLARED_ID = /^([a-z]+-\d{5})-(FR|BR|AC)-(\d+)(?:\.(\d+))?$/
const DOC_PREFIX = /^([a-z]+-\d{5})-/
/** The same id, hunted inside a cell that holds more than one — or part of one. */
const ITEM_ID = /[a-z]+-\d{5}-(?:FR|BR|AC)-\d+(?:\.\d+)?/g
/**
 * A line opening with a bold id, allowing for the list marker or the leading
 * pipe a declaration would carry. Bold is the declaration form and prose quotes
 * ids in backticks (decision-00005 §4), so such a line that is not one of the
 * two shapes is a declaration that lost its shape.
 */
const SUSPECT_LINE = /^(?:[-*+][ \t]+|\d+[.)][ \t]+|\|[ \t]*)?\*\*([^*]+)\*\*/
/** The `(spec-00001-FR-1)` an acceptance criterion carries to say what it verifies. */
const ATTRIBUTION = /^\(([^)]+)\)[ \t]*/
const TEST_COLUMN = /test|测试/i
const RESULT_COLUMN = /result|结果/i
const EVIDENCE_COLUMN = /evidence|证据/i
const PASS = 'pass'

/** Only spec and rule declare requirement items; other types are out of scope (spec-00001 §6). */
const ITEM_TYPES = new Set(['spec', 'rule'])

const markdown = unified().use(remarkParse).use(remarkGfm)

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

/**
 * The three kinds of spec-00001-FR-40:
 * - `item-shape` — a line opening with a bold item id in neither declaration shape;
 * - `checklist-row` — a checklist row whose first cell is not exactly one id
 *   (a range, or several ids in one cell);
 * - `unattributable` — a row or a criterion with nowhere to belong (FR-33).
 */
export type DiagnosticKind = 'item-shape' | 'checklist-row' | 'unattributable'

export interface Diagnostic {
  kind: DiagnosticKind
  /** The record the offending row came from; absent when the line is the document's own. */
  recordId?: string
  /** The id the line or row named. */
  declaredId?: string
  /** The item a criterion claimed to verify, when no such item exists. */
  attributedTo?: string
  /** 1-based line of the offending line, counted in the body — front matter excluded. */
  line?: number
  /** The source line itself, for the panel to show (design-00002 §9). */
  text?: string
}

/** A diagnostic as the graph carries it: the same row, told which document it belongs to. */
export interface GraphDiagnostic extends Diagnostic {
  docId: string
}

export interface ItemsView {
  items: RequirementItem[]
  diagnostics: Diagnostic[]
}

export interface DocBody {
  id: string
  body: string
}

/** Every record read once: the rows it offers and the rows that lost their shape. */
export interface RecordScan {
  rows: ScannedRow[]
  malformed: MalformedRow[]
}

interface ScannedRow {
  row: AcceptanceRow
  line: number
  text: string
}

interface MalformedRow {
  recordId: string
  /** The document the row was trying to verify, taken from the first id it names. */
  prefix: string
  line: number
  text: string
}

type ItemDraft = Omit<RequirementItem, 'coverage'>

interface Declaration {
  id: string
  text: string
  line: number
  /** `[prefix, kind, number, sub]` when the id is a requirement id. */
  parts: RegExpExecArray | null
}

interface BodyParse {
  root: Root
  declarations: Declaration[]
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

/** Read every record once, so a graph-wide pass does not re-parse them per document. */
export function scanRecords(records: DocBody[]): RecordScan {
  const scan: RecordScan = { rows: [], malformed: [] }
  for (const record of records) scanRecord(record, scan)
  return scan
}

/** The items of one spec or rule, with their criteria, verifying rows, coverage, and diagnostics. */
export function requirementView(doc: DocBody, records: DocBody[]): ItemsView {
  return requirementViewFrom(doc, scanRecords(records))
}

/** The same view, over records that were already scanned. */
export function requirementViewFrom(doc: DocBody, scan: RecordScan): ItemsView {
  const prefix = DOC_PREFIX.exec(doc.id)?.[1]
  if (prefix === undefined) return { items: [], diagnostics: [] }

  const parsed = parseBody(doc.body)
  const declarations = parsed.declarations.filter((declaration) => declaration.parts?.[1] === prefix)
  const items = declarations.filter(isItem).sort(byNumber).map(toDraft)
  const lines = doc.body.split('\n')
  const diagnostics: Diagnostic[] = [
    ...shapeDiagnostics(parsed, lines, prefix),
    ...scan.malformed
      .filter((row) => row.prefix === prefix)
      .map(({ recordId, line, text }) => ({ kind: 'checklist-row' as const, recordId, line, text })),
  ]
  attachCriteria(items, declarations.filter(isCriterion).sort(byNumber), lines, diagnostics)
  attachRows(items, ownRows(scan.rows, prefix), diagnostics)

  return { items: items.map((item) => ({ ...item, coverage: coverageOf(item) })), diagnostics }
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

function attachCriteria(
  items: ItemDraft[],
  criteria: Declaration[],
  lines: string[],
  diagnostics: Diagnostic[],
): void {
  for (const criterion of criteria) {
    const attributedTo = ATTRIBUTION.exec(criterion.text)?.[1]
    const owner = items.find((item) => item.id === attributedTo)
    if (!owner) {
      diagnostics.push({
        kind: 'unattributable',
        declaredId: criterion.id,
        attributedTo,
        line: criterion.line,
        text: lines[criterion.line - 1]!.trim(),
      })
      continue
    }
    owner.criteria.push({ id: criterion.id, text: criterion.text.replace(ATTRIBUTION, ''), rows: [] })
  }
}

function attachRows(items: ItemDraft[], scanned: ScannedRow[], diagnostics: Diagnostic[]): void {
  const criteria = items.flatMap((item) => item.criteria)
  for (const { row, line, text } of scanned) {
    const target =
      items.find((item) => item.id === row.targetId) ?? criteria.find((criterion) => criterion.id === row.targetId)
    if (target) target.rows.push(row)
    else
      diagnostics.push({
        kind: 'unattributable',
        recordId: row.recordId,
        declaredId: row.targetId,
        line,
        text,
      })
  }
}

/** The scanned rows, across every record, that name something belonging to `prefix`. */
function ownRows(rows: ScannedRow[], prefix: string): ScannedRow[] {
  return rows.filter((scanned) => DECLARED_ID.exec(scanned.row.targetId)?.[1] === prefix)
}

/**
 * The rows of a record's acceptance checklists: a table whose header names a
 * test and a result column, and whose first cell is the id being verified. A
 * table of ids and prose (the amendment tables) is not one; an Evidence column
 * neither adds nor removes anything (spec-00001-FR-32).
 */
export function acceptanceRows(record: DocBody): AcceptanceRow[] {
  const scan: RecordScan = { rows: [], malformed: [] }
  scanRecord(record, scan)
  return scan.rows.map((scanned) => scanned.row)
}

function scanRecord(record: DocBody, scan: RecordScan): void {
  walk(markdown.parse(record.body), (node) => {
    if (node.type !== 'table') return
    // A GFM table exists only where a delimiter row followed a header row.
    const header = node.children[0]!
    const columns = verificationColumns(header.children.map((cell) => cellText(record.body, cell)))
    if (columns === undefined) return
    for (const row of node.children.slice(1)) readChecklistRow(record, row, columns, scan)
  })
}

function readChecklistRow(record: DocBody, row: TableRow, columns: VerificationColumns, scan: RecordScan): void {
  const cells = row.children.map((cell) => cellText(record.body, cell))
  // A table row always has a first cell, and only the first cell says what the row verifies.
  const targetId = cells[0]!
  const line = row.position!.start.line
  const text = slice(record.body, row).trim()
  if (DECLARED_ID.test(targetId)) {
    scan.rows.push({ row: toRow(record.id, targetId, cells, columns), line, text })
    return
  }
  // Not one id, but id-shaped all the same: a range or a cell holding several
  // (`docs/record/README.md` forbids both). A first cell of document ids is a
  // different table and stays silent.
  const prefix = DOC_PREFIX.exec(targetId.match(ITEM_ID)?.[0] ?? '')?.[1]
  if (prefix === undefined) return
  scan.malformed.push({ recordId: record.id, prefix, line, text })
}

function toRow(recordId: string, targetId: string, cells: string[], columns: VerificationColumns): AcceptanceRow {
  const evidence = columns.evidence === undefined ? '' : (cells[columns.evidence] ?? '')
  return {
    recordId,
    targetId,
    test: cells[columns.test] ?? '',
    result: cells[columns.result] ?? '',
    // Left out rather than left empty: a checklist with no Evidence column has
    // no such field to show (design-00001 §7, spec-00001-AC-37.8).
    ...(evidence === '' ? {} : { evidence }),
  }
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

/**
 * Lines that open with a bold id of this document and are neither declaration
 * shape (spec-00001-AC-40.2, AC-40.8). Code and raw HTML are quoted, not
 * declared, so they are left alone.
 */
function shapeDiagnostics(parsed: BodyParse, lines: string[], prefix: string): Diagnostic[] {
  const declared = new Set(parsed.declarations.map((declaration) => declaration.line))
  const quoted = literalLines(parsed.root)
  const found: Diagnostic[] = []
  lines.forEach((text, index) => {
    const line = index + 1
    if (declared.has(line) || quoted.has(line)) return
    const id = SUSPECT_LINE.exec(text)?.[1]?.trim()
    if (id === undefined || DECLARED_ID.exec(id)?.[1] !== prefix) return
    found.push({ kind: 'item-shape', declaredId: id, line, text: text.trim() })
  })
  return found
}

function literalLines(root: Root): Set<number> {
  const lines = new Set<number>()
  walk(root, (node) => {
    if (node.type !== 'code' && node.type !== 'html') return
    for (let line = node.position!.start.line; line <= node.position!.end.line; line++) lines.add(line)
  })
  return lines
}

/** Declarations whose id belongs to this document — anything else is somebody's quotation. */
function ownDeclarations(doc: DocBody): Declaration[] {
  const prefix = DOC_PREFIX.exec(doc.id)?.[1]
  if (prefix === undefined) return []
  return parseBody(doc.body).declarations.filter((declaration) => declaration.parts?.[1] === prefix)
}

function parseBody(body: string): BodyParse {
  const root = markdown.parse(body)
  return { root, declarations: declarationsOf(root, body) }
}

/**
 * Both declaration shapes, taken from the AST: an unordered list item opening
 * with a bold id, or a decision-table row whose first cell is that bold id and
 * nothing else. Both must start the line — a nested item is somebody's detail,
 * not a declaration (`docs/spec/README.md`, 「整行起头」).
 */
function declarationsOf(root: Root, body: string): Declaration[] {
  const found: Declaration[] = []
  for (const node of root.children) {
    if (node.type === 'list' && !node.ordered) {
      for (const item of node.children) {
        const declaration = item.position!.start.column === 1 ? listDeclaration(item, body) : undefined
        if (declaration) found.push(declaration)
      }
    } else if (node.type === 'table') {
      for (const row of node.children.slice(1)) {
        const declaration = row.position!.start.column === 1 ? rowDeclaration(row, body) : undefined
        if (declaration) found.push(declaration)
      }
    }
  }
  return found
}

/** `- **spec-00001-FR-1** (Event) …`, its paragraph carrying the continuation lines. */
function listDeclaration(item: ListItem, body: string): Declaration | undefined {
  const paragraph = item.children[0]
  if (paragraph?.type !== 'paragraph') return undefined
  const strong = paragraph.children[0]
  if (strong?.type !== 'strong') return undefined
  return declaration(
    boldText(body, strong.position!.start.offset!, strong.position!.end.offset!),
    body.slice(strong.position!.end.offset!, paragraph.position!.end.offset!),
    paragraph.position!.start.line,
  )
}

/** `| **rule-00001-BR-2** | living doc | … |`, the remaining cells as its text. */
function rowDeclaration(row: TableRow, body: string): Declaration | undefined {
  const first = row.children[0]
  const strong = first?.children[0]
  if (first === undefined || first.children.length !== 1 || strong?.type !== 'strong') return undefined
  return declaration(
    boldText(body, strong.position!.start.offset!, strong.position!.end.offset!),
    row.children
      .slice(1)
      .map((cell) => cellText(body, cell))
      .join(' | '),
    row.position!.start.line,
  )
}

/** What stands between the `**` markers, as written — an id is plain text or it is no id. */
function boldText(body: string, start: number, end: number): string {
  return body.slice(start + 2, end - 2).trim()
}

function declaration(id: string, text: string, line: number): Declaration {
  return { id, text: text.replace(/\s+/g, ' ').trim(), line, parts: DECLARED_ID.exec(id) }
}

/** A cell's own text: mdast spans a cell from its opening pipe to the next one, so both go. */
function cellText(body: string, cell: TableCell): string {
  return clean(slice(body, cell).replace(/^\|/, '').replace(/\|$/, ''))
}

function slice(body: string, node: Nodes): string {
  return body.slice(node.position!.start.offset!, node.position!.end.offset!)
}

function clean(text: string): string {
  return text.replace(/[*`]/g, '').trim()
}

function walk(node: Nodes, visitor: (node: Nodes) => void): void {
  visitor(node)
  if ('children' in node) for (const child of node.children) walk(child, visitor)
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
