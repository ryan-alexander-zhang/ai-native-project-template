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
} catch (error) {
  // A missing or invalid flow config is fatal: there is no built-in default (spec-00001-FR-15).
  console.error(error instanceof ConfigError ? error.message : error)
  process.exit(1)
}
