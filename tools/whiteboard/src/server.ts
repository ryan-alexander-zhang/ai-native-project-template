import type { Server } from 'node:http'
import express, { type Express, type NextFunction, type Request, type Response } from 'express'
import { WebSocketServer } from 'ws'
import { type Expectation, findProduct, markProduct, productProblems } from './advance.ts'
import type { FlowConfig } from './config.ts'
import { ConflictError, DocService } from './docService.ts'
import { SessionBusyError, SessionManager, type SessionOutcome, type SpawnPty } from './sessionManager.ts'
import { spawnPty } from './pty.ts'
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
  /** Findings from the last advance, folded into the graph until the next one. */
  private lastFinding?: { docId: string; problems: string[] }

  constructor(options: BoardOptions) {
    const { repoRoot, docsDir, config, spawn = spawnPty } = options
    this.docs = new DocService(repoRoot, docsDir, config)
    this.sessions = new SessionManager({
      agent: config.agents[0]!,
      repoRoot,
      spawn,
      onExit: (expectation) => this.finishSession(expectation),
    })
    this.app = this.buildApp(config)
  }

  graph() {
    const graph = this.docs.graph()
    return this.lastFinding ? markProduct(graph, this.lastFinding.docId, this.lastFinding.problems) : graph
  }

  /** Commit what the session wrote, then check it against what was asked for (spec-00001-FR-17). */
  private async finishSession(expectation: Expectation): Promise<SessionOutcome> {
    const product = findProduct(this.docs.graph(), expectation.idPrefix)
    if (!product) {
      this.lastFinding = undefined
      const outcome = await this.docs.commitSessionChanges(expectation.sourceId)
      return { problems: [], committed: outcome.committed, error: outcome.error }
    }
    const problems = productProblems(product, expectation)
    this.lastFinding = problems.length > 0 ? { docId: product.id, problems } : undefined
    const outcome = await this.docs.commitSessionChanges(product.id)
    return { docId: product.id, problems, committed: outcome.committed, error: outcome.error }
  }

  private buildApp(config: FlowConfig): Express {
    const app = express()
    app.use(express.json({ limit: '4mb' }))
    app.use(express.static(new URL('../dist/web', import.meta.url).pathname))

    app.get('/api/graph', (_req, res) => res.json(this.graph()))
    app.get('/api/config', (_req, res) => res.json(config))

    app.get('/api/docs/:id', (req, res) => res.json(this.docs.read(req.params.id)))
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
    return this.sessions.start({
      targetType,
      idPrefix: idPrefix(targetType, allocateNumber(this.docs.graph(), targetType)),
      carry: step.carry,
      sourceId,
    })
  }

  /** Bind the terminal socket: replay the buffer, then stream both ways. */
  listen(port: number): Server {
    const server = this.app.listen(port)
    const wss = new WebSocketServer({ server, path: '/api/terminal' })
    wss.on('connection', (socket) => {
      let attached: { buffer: string; detach: () => void }
      try {
        attached = this.sessions.attach((data) => socket.send(data))
      } catch {
        socket.close()
        return
      }
      socket.send(attached.buffer)
      socket.on('message', (data) => this.sessions.write(data.toString()))
      socket.on('close', () => attached.detach())
    })
    return server
  }
}

const STATUS_BY_ERROR: Array<[new (...args: never[]) => Error, number]> = [
  [ConflictError, 409],
  [SessionBusyError, 409],
  [WorkflowError, 422],
]

function errorHandler(error: Error, _req: Request, res: Response, _next: NextFunction): void {
  const match = STATUS_BY_ERROR.find(([type]) => error instanceof type)
  res.status(match?.[1] ?? 500).json({ error: error.message })
}
