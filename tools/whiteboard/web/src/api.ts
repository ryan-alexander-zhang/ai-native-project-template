import type { FlowConfig, FlowStep } from '../../src/config.ts'
import type { DocContent, DocGraph } from '../../src/docRepository.ts'
import type { ActionResult } from '../../src/docService.ts'
import type { ItemsView } from '../../src/requirements.ts'
import type { SessionInfo } from '../../src/sessionManager.ts'

export type { DocContent, DocGraph, FlowConfig, FlowStep, ItemsView, SessionInfo }

/** A refused action; `status` is what the board shows the user (409 conflict, 422 rejected). */
export class ApiError extends Error {
  readonly status: number
  /**
   * The gaps a `resolved` gate refusal names one by one — item ids, or ids it
   * could not resolve (spec-00001-FR-52). Only that refusal carries them, so
   * their presence is what tells the gate's 422 from any other.
   */
  readonly gaps?: string[]

  constructor(status: number, message: string, gaps?: string[]) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.gaps = gaps
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'content-type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const payload = await response.json()
  if (!response.ok) {
    throw new ApiError(response.status, payload.error ?? response.statusText, payload.gaps)
  }
  return payload as T
}

export const api = {
  graph: () => request<DocGraph>('GET', '/api/graph'),
  config: () => request<FlowConfig>('GET', '/api/config'),
  doc: (id: string) => request<DocContent>('GET', `/api/docs/${id}`),
  items: (id: string) => request<ItemsView>('GET', `/api/docs/${id}/items`),
  save: (id: string, content: string, baseHash: string) =>
    request<ActionResult>('PUT', `/api/docs/${id}`, { content, baseHash }),
  transitions: (id: string) => request<string[]>('GET', `/api/docs/${id}/transitions`),
  setStatus: (id: string, to: string) => request<ActionResult>('POST', `/api/docs/${id}/status`, { to }),
  accept: (id: string) => request<ActionResult>('POST', `/api/docs/${id}/review`, { action: 'accept' }),
  nextSteps: (id: string) => request<FlowStep[]>('GET', `/api/docs/${id}/next-steps`),
  session: () => request<{ current: SessionInfo | null }>('GET', '/api/sessions'),
  advance: (sourceId: string, targetType: string) =>
    request<SessionInfo>('POST', '/api/sessions', { sourceId, targetType }),
  // Clarify, ask and audit are sessions, not writes: the agent does the
  // questioning, the answering and the auditing in the terminal
  // (spec-00001-FR-9, FR-47, FR-50).
  clarify: (docId: string) => request<SessionInfo>('POST', '/api/sessions/clarify', { docId }),
  ask: (docId: string) => request<SessionInfo>('POST', '/api/sessions/ask', { docId }),
  audit: (docId: string) => request<SessionInfo>('POST', '/api/sessions/audit', { docId }),
  // The way out of a session that will not end by itself; what comes back is the
  // session as it finished (spec-00001-FR-49).
  stopSession: () => request<SessionInfo>('DELETE', '/api/sessions'),
}
