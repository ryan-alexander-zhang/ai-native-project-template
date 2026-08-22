import { FileWarning, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import type { DocNode, GraphIssue } from '../../src/docRepository.ts'
import type { GraphDiagnostic } from '../../src/requirements.ts'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

/** A detail is evidence, not reading matter: enough of it to recognise the line. */
const DETAIL_LIMIT = 80

function truncate(text: string): string {
  return text.length > DETAIL_LIMIT ? `${text.slice(0, DETAIL_LIMIT)}…` : text
}

interface ListDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  icon: ReactNode
  title: string
  description: string
  label: string
  children: ReactNode
}

/**
 * The carrier the three drilldowns share (design-00002 §3): the command
 * palette's full-screen dialog, closed by Escape or by its close control. Only
 * one list is ever open, which is the accepted cost of not inventing a third
 * docking rule for a read-and-leave list.
 */
function ListDialog({ open, onOpenChange, icon, title, description, label, children }: ListDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {icon}
            {title}
          </DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <ul aria-label={label} className="max-h-[60vh] overflow-y-auto">
          {children}
        </ul>
      </DialogContent>
    </Dialog>
  )
}

/** Every row is a real button: Tab reaches it and Enter fires it (design-00002 §6). */
function Row({ onPick, children }: { onPick: () => void; children: ReactNode }) {
  return (
    <li>
      <button
        type="button"
        onClick={onPick}
        className="hover:bg-accent flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs"
      >
        {children}
      </button>
    </li>
  )
}

export interface IssueListProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  issues: GraphIssue[]
  /** The graph the colliding ids are read off; nothing else about a node is needed. */
  nodes: DocNode[]
  onPick: (nodeId: string) => void
}

/**
 * The anomaly list of spec-00002-FR-13: every issue the graph carries, with
 * where it came from and what it says. Clicking one goes to its node (FR-15) —
 * a broken edge to the document that declared it, never to the id it missed.
 */
export function IssueList({ open, onOpenChange, issues, nodes, onPick }: IssueListProps) {
  return (
    <ListDialog
      open={open}
      onOpenChange={onOpenChange}
      icon={<TriangleAlert className="size-4" aria-hidden />}
      title="Anomalies"
      description="Every anomalous document and broken link. Pick one to go to the document it is on."
      label="Anomalies"
    >
      {issues.map((issue, index) => (
        <Row
          // Two problems of one document read the same but for their message, and
          // even that may repeat, so the position is the only thing telling them apart.
          key={`${issue.nodeId}-${index}`}
          onPick={() => onPick(issue.nodeId)}
        >
          <span className="shrink-0 truncate font-mono">{issue.path}</span>
          <IssueId issue={issue} nodes={nodes} />
          <span className="text-destructive truncate">{issue.message}</span>
        </Row>
      ))}
    </ListDialog>
  )
}

/**
 * The id shown beside the path, in the three cases of design-00001 §7. The path
 * is always the source; which id stands next to it is not one test but three:
 *
 * - the key differs from the path — the file parsed out a usable document id;
 * - the key **is** the path and the node collides — the colliding id is the very
 *   content of this anomaly, and hiding it would leave two rows reading alike;
 * - the key is the path and nothing collides — the file has no id to show.
 */
function IssueId({ issue, nodes }: { issue: GraphIssue; nodes: DocNode[] }) {
  const declared =
    issue.nodeId !== issue.path
      ? issue.nodeId
      : nodes.find((node) => node.id === issue.nodeId)?.duplicateOf
  if (declared === undefined) return null
  return <span className="text-muted-foreground shrink-0 truncate font-mono">{declared}</span>
}

export interface DiagnosticListProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  diagnostics: GraphDiagnostic[]
  onPick: (docId: string) => void
}

/**
 * The diagnostics list of spec-00002-FR-14: the source document, the kind, and
 * the detail. The third column is «detail», not «source line», because the kinds
 * do not all have one — `relation-field` is read off the front matter, which has
 * no body line and therefore no line number either, so that column is simply
 * left empty for it (design-00002 §3).
 *
 * It shares nothing with the anomaly list but the carrier: a document that is
 * both an anomalous node and the source of a diagnostic is listed in each for
 * its own reason (spec-00002-AC-14.3).
 */
export function DiagnosticList({ open, onOpenChange, diagnostics, onPick }: DiagnosticListProps) {
  return (
    <ListDialog
      open={open}
      onOpenChange={onOpenChange}
      icon={<FileWarning className="size-4" aria-hidden />}
      title="Diagnostics"
      description="Every parse diagnostic. Pick one to go to the document it came from."
      label="Diagnostics"
    >
      {diagnostics.map((entry, index) => (
        <Row key={`${entry.docId}-${entry.kind}-${index}`} onPick={() => onPick(entry.docId)}>
          <span className="shrink-0 truncate font-mono">{entry.docId}</span>
          <span className="shrink-0 font-mono">{entry.kind}</span>
          {entry.line === undefined ? null : (
            <span className="text-muted-foreground shrink-0 font-mono">line {entry.line}</span>
          )}
          {entry.text === undefined ? null : (
            <span className="text-muted-foreground truncate">{truncate(entry.text)}</span>
          )}
        </Row>
      ))}
    </ListDialog>
  )
}
