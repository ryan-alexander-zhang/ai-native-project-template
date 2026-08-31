#!/usr/bin/env node
import { join } from 'node:path'
import { CONFIG_FILE, ConfigError, findRepoRoot, loadFlowConfig } from '../src/config.ts'
import { Board } from '../src/server.ts'

const port = Number(process.env.PORT ?? 4173)

try {
  // Start from wherever the user is; the repo is the nearest owner of a flow config.
  const repoRoot = findRepoRoot(process.cwd())
  const config = loadFlowConfig(join(repoRoot, CONFIG_FILE))
  const board = new Board({ repoRoot, docsDir: join(repoRoot, 'docs'), config })

  const server = board.listen(port)
  server.on('listening', () => {
    console.log(`whiteboard: http://localhost:${server.address().port} — docs of ${repoRoot}`)
  })
  server.on('error', (error) => {
    console.error(`whiteboard: cannot listen on port ${port} — ${error.message}`)
    process.exit(1)
  })

  // A normal shutdown wraps up every running session before the process goes, so
  // what the agents wrote is committed and their transcripts are on disk
  // (spec-00003-FR-9). `board.shutdown()` is idempotent: a second Ctrl-C joins
  // the one already running rather than exiting out from under it — Node's
  // convention of the second signal being the impatient one is deliberately not
  // followed, because the impatient exit is exactly the lost commit this handler
  // exists to prevent, and the wait is bounded by the signal escalation
  // (issue-00012) rather than by the agents' manners.
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      void board.shutdown().then(() => {
        server.close()
        process.exit(0)
      })
    })
  }
} catch (error) {
  // A missing or invalid flow config is fatal: there is no built-in default (spec-00001-FR-15).
  console.error(error instanceof ConfigError ? error.message : error)
  process.exit(1)
}
