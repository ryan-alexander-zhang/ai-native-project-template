import type { Server } from 'node:http'
import express, { type Express, type NextFunction, type Request, type Response } from 'express'
import { WebSocketServer } from 'ws'
import { type Expectation, findProduct, markProduct, productProblems, taskInstruction } from './advance.ts'
import type { FlowConfig } from './config.ts'
import { ConflictError, DocService } from './docService.ts'
import type { DirtySnapshot } from './gitLayer.ts'
import {
  NoSessionError,
  SessionBusyError,
  SessionManager,
  type SessionOutcome,
  type SessionPlan,
  type SpawnPty,
} from './sessionManager.ts'
import { spawnPty } from './pty.ts'
import { DocsWatcher } from './watcher.ts'
import { WorkflowError, allocateNumber, idPrefix } from './workflow.ts'

export interface BoardOptions {
  repoRoot: string
  docsDir: string
  config: FlowConfig
  spawn?: SpawnPty
}

/** Wires the modules into one board: doc service, session manager, and the HTTP/WS surface. */
export class Board {
  readonly app: Express
  readonly docs: DocService
  readonly sessions: SessionManager
  /** Live only while the board is listening: a board nobody can connect to has nobody to tell. */
  readonly watcher: DocsWatcher
  /** Findings from the last advance, folded into the graph until the next one. */
  private lastFinding?: { docId: string; problems: string[] }

  constructor(options: BoardOptions) {
    const { repoRoot, docsDir, config, spawn = spawnPty } = options
    this.docs = new DocService(repoRoot, docsDir, config)
    this.watcher = new DocsWatcher(docsDir)
    this.sessions = new SessionManager({
      agent: config.agents[0]!,
      repoRoot,
      spawn,
      snapshot: () => this.docs.snapshotDocs(),
      onExit: (plan) => this.finishSession(plan),
    })
    this.app = this.buildApp(config)
  }

  graph() {
    const graph = this.docs.graph()
    return this.lastFinding ? markProduct(graph, this.lastFinding.docId, this.lastFinding.problems) : graph
  }

  /** Commit what the session wrote, then check it against what was asked for (spec-00001-FR-17). */
  private async finishSession(plan: SessionPlan): Promise<SessionOutcome> {
    // What the session inherited, taken when it started: only what moved since
    // is its own to commit (spec-00001-AC-14.5).
    const before = this.sessions.baseline()
    // Clarify and ask were asked for no new document, so there is nothing to
    // check: their commit is named by the kind and carries the document they
    // were about (spec-00001-AC-14.7, AC-14.8).
    if (plan.expectation === undefined) {
      const outcome = await this.docs.commitSessionChanges(plan.sourceId, before, plan.kind)
      return { docId: plan.sourceId, problems: [], committed: outcome.committed, error: outcome.error }
    }
    return this.finishAdvance(plan.expectation, before)
  }

  private async finishAdvance(expectation: Expectation, before: DirtySnapshot): Promise<SessionOutcome> {
    const product = findProduct(this.docs.graph(), expectation.idPrefix)
    if (!product) {
      this.lastFinding = undefined
      const outcome = await this.docs.commitSessionChanges(expectation.sourceId, before)
      return { problems: [], committed: outcome.committed, error: outcome.error }
    }
    const problems = productProblems(product, expectation)
    this.lastFinding = problems.length > 0 ? { docId: product.id, problems } : undefined
    const outcome = await this.docs.commitSessionChanges(product.id, before)
    return { docId: product.id, problems, committed: outcome.committed, error: outcome.error }
  }

  private buildApp(config: FlowConfig): Express {
    const app = express()
    app.use(express.json({ limit: '4mb' }))
    app.use(express.static(new URL('../dist/web', import.meta.url).pathname))

    app.get('/api/graph', (_req, res) => res.json(this.graph()))
    app.get('/api/config', (_req, res) => res.json(config))

    app.get('/api/docs/:id', (req, res) => res.json(this.docs.read(req.params.id)))
    app.get('/api/docs/:id/items', (req, res) => res.json(this.docs.items(req.params.id)))
    app.get('/api/docs/:id/transitions', (req, res) => res.json(this.docs.transitions(req.params.id)))
    app.get('/api/docs/:id/next-steps', (req, res) => res.json(this.docs.nextSteps(req.params.id)))

    app.put('/api/docs/:id', async (req, res) => {
      res.json(await this.docs.save(req.params.id, req.body.content, req.body.baseHash))
    })
    app.post('/api/docs/:id/status', async (req, res) => {
      res.json(await this.docs.changeStatus(req.params.id, req.body.to))
    })
    app.post('/api/docs/:id/review', async (req, res) => {
      res.json(await this.docs.review(req.params.id, req.body))
    })

    app.get('/api/sessions', (_req, res) => res.json({ current: this.sessions.current() }))
    app.post('/api/sessions', (req, res) => res.json(this.startSession(req.body)))
    // The other two session kinds: same channel, same one slot (spec-00001-FR-18),
    // each with its own ruling (FR-9, FR-47) made in the doc service.
    app.post('/api/sessions/clarify', (req, res) => {
      res.json(this.sessions.start(this.docs.clarifyPlan(docIdOf(req.body))))
    })
    app.post('/api/sessions/ask', (req, res) => {
      res.json(this.sessions.start(this.docs.askPlan(docIdOf(req.body))))
    })
    // The way out of a session that will not end by itself (spec-00001-FR-49);
    // the wrap-up it answers with has already run.
    app.delete('/api/sessions', async (_req, res) => {
      res.json(await this.sessions.terminate())
    })

    app.use(errorHandler)
    return app
  }

  /** spec-00001-FR-10 and FR-11: only a step the flow config declares may be started. */
  private startSession(body: { sourceId?: string; targetType?: string }) {
    const { sourceId, targetType } = body
    if (typeof sourceId !== 'string' || typeof targetType !== 'string') {
      throw new WorkflowError('an advance needs a sourceId and a targetType')
    }
    const step = this.docs.nextSteps(sourceId).find((candidate) => candidate.next === targetType)
    if (!step) {
      throw new WorkflowError(`${targetType} is not a next step of ${sourceId}`)
    }
    const expectation: Expectation = {
      targetType,
      idPrefix: idPrefix(targetType, allocateNumber(this.docs.graph(), targetType)),
      carry: step.carry,
      sourceId,
    }
    return this.sessions.start({ kind: 'advance', sourceId, instruction: taskInstruction(expectation), expectation })
  }

  /**
   * Serve, and open the two sockets: the session terminal, and the docs-change
   * signal every board follows (spec-00001-FR-42). Each socket server takes the
   * upgrade handed to it by the one router below — two of them bound to the same
   * http server would each abort the other's handshakes.
   */
  listen(port: number): Server {
    const server = this.app.listen(port)
    const terminals = new WebSocketServer({ noServer: true })
    const events = new WebSocketServer({ noServer: true })
    const routes: Record<string, WebSocketServer> = { '/api/terminal': terminals, '/api/events': events }

    server.on('upgrade', (request, socket, head) => {
      const route = routes[new URL(request.url ?? '/', 'http://board').pathname]
      if (!route) {
        socket.destroy()
        return
      }
      route.handleUpgrade(request, socket, head, (connection) => route.emit('connection', connection, request))
    })

    terminals.on('connection', (socket) => {
      let attached: { buffer: string; detach: () => void }
      try {
        attached = this.sessions.attach((data) => socket.send(data))
      } catch {
        socket.close()
        return
      }
      socket.send(attached.buffer)
      // The two kinds of message the terminal sends are told apart by frame
      // type, never by their bytes: a text frame is stdin as it was typed, and a
      // binary frame is the terminal's size (design-00001 §7, issue-00009).
      socket.on('message', (data, isBinary) => {
        if (!isBinary) {
          this.sessions.write(data.toString())
          return
        }
        const size = parseSize(data.toString())
        if (size) this.sessions.resize(size.cols, size.rows)
      })
      socket.on('close', () => attached.detach())
    })

    // The frame is the whole message: a board that gets one re-reads the graph
    // and the items it is showing (design-00001 §6).
    events.on('connection', (socket) => {
      const unsubscribe = this.watcher.subscribe(() => {
        if (socket.readyState === socket.OPEN) socket.send('')
      })
      socket.on('close', unsubscribe)
    })

    this.watcher.start()
    server.on('close', () => void this.watcher.close())
    return server
  }
}

/** A clarify or ask request names the one document it is about. */
function docIdOf(body: { docId?: string }): string {
  if (typeof body.docId !== 'string') {
    throw new WorkflowError('a clarify or ask session needs a docId')
  }
  return body.docId
}

const isTerminalSize = (value: unknown): value is number =>
  typeof value === 'number' && Number.isInteger(value) && value > 0

/**
 * A terminal size control frame: the columns and rows the embedded terminal is
 * drawing into. A frame that carries no readable pair is dropped — it is not
 * stdin either, and the session keeps the size it had.
 */
function parseSize(frame: string): { cols: number; rows: number } | undefined {
  try {
    const { cols, rows } = JSON.parse(frame) as { cols?: unknown; rows?: unknown }
    return isTerminalSize(cols) && isTerminalSize(rows) ? { cols, rows } : undefined
  } catch {
    return undefined
  }
}

const STATUS_BY_ERROR: Array<[new (...args: never[]) => Error, number]> = [
  [ConflictError, 409],
  [SessionBusyError, 409],
  [NoSessionError, 404],
  [WorkflowError, 422],
]

function errorHandler(error: Error, _req: Request, res: Response, _next: NextFunction): void {
  const match = STATUS_BY_ERROR.find(([type]) => error instanceof type)
  res.status(match?.[1] ?? 500).json({ error: error.message })
}
