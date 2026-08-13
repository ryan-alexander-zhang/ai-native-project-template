import type { DocNode } from '../../src/docRepository.ts'
import { statusColour, statusLabel } from './status.ts'

/** A document on the canvas: type, id, title, and its status in colour. */
export function NodeCard({ node, selected }: { node: DocNode; selected: boolean }) {
  return (
    <div className={`node-card${selected ? ' node-card--selected' : ''}`} data-testid={`node-${node.id}`}>
      <div className="node-card__head">
        <span className="node-card__type">{node.type ?? '—'}</span>
        <span className="node-card__status" style={{ background: statusColour(node) }}>
          {statusLabel(node)}
        </span>
      </div>
      <div className="node-card__title">{node.title}</div>
      <div className="node-card__id">{node.id}</div>
      {node.ok ? null : <div className="node-card__problems">{node.problems.join('; ')}</div>}
    </div>
  )
}
