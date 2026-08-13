import { useState } from 'react'
import type { FlowStep } from '../../src/config.ts'
import type { DocNode } from '../../src/docRepository.ts'

export interface ToolbarProps {
  node: DocNode
  transitions: string[]
  nextSteps: FlowStep[]
  onEdit: () => void
  onStatus: (to: string) => void
  onAccept: () => void
  onClarify: (questions: string[]) => void
  onAdvance: (targetType: string) => void
}

/**
 * The floating toolbar of spec-00001-FR-3. A document with front matter problems
 * offers only the editor — the way to repair it (spec-00001-AC-2.4).
 */
export function Toolbar(props: ToolbarProps) {
  const { node, transitions, nextSteps, onEdit, onStatus, onAccept, onClarify, onAdvance } = props
  const [clarifying, setClarifying] = useState(false)
  const [questions, setQuestions] = useState('')

  return (
    <div className="toolbar" role="toolbar" aria-label={`Actions for ${node.id}`}>
      <button type="button" onClick={onEdit}>
        Edit
      </button>

      {node.ok ? (
        <>
          <label className="toolbar__status">
            Status
            <select value="" onChange={(event) => onStatus(event.target.value)} aria-label="Change status">
              <option value="" disabled>
                {node.status}
              </option>
              {transitions.map((to) => (
                <option key={to} value={to}>
                  {to}
                </option>
              ))}
            </select>
          </label>

          <button type="button" onClick={onAccept}>
            Accept
          </button>
          <button type="button" onClick={() => setClarifying((open) => !open)}>
            Clarify
          </button>

          <label className="toolbar__next">
            <select
              value=""
              onChange={(event) => onAdvance(event.target.value)}
              aria-label="Advance to the next step"
              disabled={nextSteps.length === 0}
            >
              <option value="" disabled>
                {nextSteps.length === 0 ? 'no next step' : '+'}
              </option>
              {nextSteps.map((step) => (
                <option key={step.next} value={step.next}>
                  {step.next}
                </option>
              ))}
            </select>
          </label>
        </>
      ) : null}

      {clarifying ? (
        <form
          className="toolbar__clarify"
          onSubmit={(event) => {
            event.preventDefault()
            const list = questions
              .split('\n')
              .map((line) => line.trim())
              .filter(Boolean)
            if (list.length > 0) onClarify(list)
            setQuestions('')
            setClarifying(false)
          }}
        >
          <textarea
            aria-label="Open questions, one per line"
            value={questions}
            onChange={(event) => setQuestions(event.target.value)}
          />
          <button type="submit">Record questions</button>
        </form>
      ) : null}
    </div>
  )
}
