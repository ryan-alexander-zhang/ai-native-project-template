import type { FlowConfig, FlowStep } from '../../src/config.ts'
import type { DocContent, DocGraph } from '../../src/docRepository.ts'
import type { ActionResult } from '../../src/docService.ts'
import type { ItemsView } from '../../src/requirements.ts'
import type { SessionInfo } from '../../src/sessionManager.ts'

export type { DocContent, DocGraph, FlowConfig, FlowStep, ItemsView, SessionInfo }

/** A refused action; `status` is what the board shows the user (409 conflict, 422 rejected). */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
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
    throw new ApiError(response.status, payload.error ?? response.statusText)
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
  // Clarify and ask are sessions, not writes: the agent does the questioning in
  // the terminal (spec-00001-FR-9, FR-47).
  clarify: (docId: string) => request<SessionInfo>('POST', '/api/sessions/clarify', { docId }),
  ask: (docId: string) => request<SessionInfo>('POST', '/api/sessions/ask', { docId }),
  // The way out of a session that will not end by itself; what comes back is the
  // session as it finished (spec-00001-FR-49).
  stopSession: () => request<SessionInfo>('DELETE', '/api/sessions'),
}
