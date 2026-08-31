/**
 * React Flow's own error channel is a no-op outside a development build
 * (`createDevWarn` checks `NODE_ENV`), so a dropped edge — issue-00002's whole
 * failure mode — is silent in the built board and unobservable in tests.
 * Wiring this as `onError` gives that channel one destination in every mode.
 */
export function onFlowError(id: string, message: string): void {
  console.warn(`[whiteboard] react-flow ${id}: ${message}`)
}
